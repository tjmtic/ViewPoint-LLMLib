#!/usr/bin/env python3
"""
Desktop spike for Starpoints' on-device LLM: runs prompt_pack.json through the real shim
(lm_generate, built by scripts/host-test.sh) for each model tier and scores what matters:

  narrate  invented numbers (any number not in the facts), required names mentioned,
           degenerate output (empty / looping)
  intent   one valid JSON object, correct action + target + minutes

usage: spike/run_spike.py NAME=MODEL.gguf[:bos=<s>][:think=off|none] ... [--seeds 3]
Writes spike/results/<date>.json and prints a markdown summary.
"""
import argparse, datetime, json, os, re, subprocess, sys, tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GEN = os.path.join(ROOT, "build/host/cmake/lm_generate")
NUM = re.compile(r"\d+(?:[.,]\d+)?")
SPEED = re.compile(r"load ([\d.]+)s \| prompt (\d+) tokens in [\d.]+s \((\d+) tok/s\) \| (\d+) pieces in [\d.]+s \(([\d.]+) tok/s\)")


def chatml(system, user, bos, think):
    think_block = {"off": "<think>\n\n</think>\n\n", "none": ""}[think]
    return (f"{bos}<|im_start|>system\n{system}<|im_end|>\n"
            f"<|im_start|>user\n{user}<|im_end|>\n<|im_start|>assistant\n{think_block}")


def numbers(text):
    return {n.replace(",", "").rstrip("0").rstrip(".") if "." in n else n.replace(",", "")
            for n in NUM.findall(text)}


def degenerate(out):
    lines = [l.strip() for l in out.splitlines() if l.strip()]
    if len(out.strip()) < 20:
        return True
    words = out.split()
    return len(words) > 30 and len(set(words)) < len(words) / 4


def first_json(out):
    start = out.find("{")
    while start != -1:
        depth = 0
        for i in range(start, len(out)):
            depth += {"{": 1, "}": -1}.get(out[i], 0)
            if depth == 0:
                try:
                    return json.loads(out[start:i + 1])
                except json.JSONDecodeError:
                    break
        start = out.find("{", start + 1)
    return None


def intent_grammar(targets, time_action="show_time"):
    """GBNF: exactly the intent object, with action and target restricted to known values."""
    quoted = " | ".join('"\\"' + t + '\\""' for t in targets)
    return ('root ::= "{\\"action\\": " action ", \\"target\\": " target ", \\"minutes_from_now\\": " int "}"\n'
            'action ::= "\\"find\\"" | "\\"' + time_action + '\\"" | "\\"toggle\\""\n'
            f'target ::= "null" | {quoted}\n'
            'int ::= "0" | [1-9] [0-9]? [0-9]? [0-9]?\n')


def run(model, prompt, temperature, seed, max_tokens, grammar=None):
    paths = []
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as f:
        f.write(prompt)
        paths.append(f.name)
    args = [GEN, model, paths[0], str(max_tokens), str(temperature), "0", str(seed)]
    if grammar:
        with tempfile.NamedTemporaryFile("w", suffix=".gbnf", delete=False) as g:
            g.write(grammar)
            paths.append(g.name)
        args.append(paths[1])
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=300)
    except subprocess.TimeoutExpired:
        return None, {}
    finally:
        for path in paths:
            os.unlink(path)
    m = SPEED.search(p.stderr)
    speed = dict(zip(["load_s", "prompt_tokens", "prompt_tps", "gen_tokens", "gen_tps"],
                     map(float, m.groups()))) if m else {}
    return p.stdout, speed


def score(case, out):
    if case["kind"] == "intent":
        obj = first_json(out)
        if isinstance(obj, dict) and obj.get("action") == "advance_time":  # the v3 schema's name
            obj["action"] = "show_time"
        exp = case["expect"]
        valid = isinstance(obj, dict) and set(exp) <= set(obj)
        correct = valid and obj.get("action") == exp["action"] \
            and str(obj.get("target")).lower() == str(exp["target"]).lower() \
            and obj.get("minutes_from_now") == exp["minutes_from_now"]
        return {"json_valid": valid, "correct": bool(correct)}
    invented = sorted(numbers(out) - numbers(case["user"]))
    if "require_any" in case:
        mentions = sum(r.lower() in out.lower() for r in case["require_any"]) >= case["require_count"]
    else:
        mentions = all(r.lower() in out.lower() for r in case["require"])
    return {"invented": invented,
            "mentions": mentions,
            "degenerate": degenerate(out)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("models", nargs="+")
    ap.add_argument("--seeds", type=int, default=3)
    ap.add_argument("--kinds", default="narrate,intent", help="case kinds to run")
    ap.add_argument("--variants", default="v1,v2,v2+grammar,v3+grammar", help="intent variants to run")
    ap.add_argument("--rescore", help="re-score a saved results .json instead of running")
    ap.add_argument("--tag", default="", help="suffix for the results file name")
    args = ap.parse_args()
    pack = json.load(open(os.path.join(ROOT, "spike/prompt_pack.json")))
    if args.rescore:
        cases = {c["id"]: c for c in pack["cases"]}
        results = json.load(open(args.rescore))
        for r in results:
            if not r.get("timeout"):
                r.update(score(cases[r["case"]], r["output"]))
        summarize(results)
        return
    results = []
    os.makedirs(os.path.join(ROOT, "spike/results"), exist_ok=True)
    stamp = datetime.date.today().isoformat() + (f"-{args.tag}" if args.tag else "")
    # One line per generation as it happens, so a crash or a stall loses nothing.
    live = open(os.path.join(ROOT, f"spike/results/{stamp}.jsonl"), "w")
    for spec in args.models:
        name, rest = spec.split("=", 1)
        parts = rest.split(":")
        opts = dict(p.split("=", 1) for p in parts[1:])
        model, bos, think = parts[0], opts.get("bos", ""), opts.get("think", "off")
        settings = [("greedy", 0.0, 0)] + [(f"t0.7/s{s}", 0.7, s) for s in range(1, args.seeds + 1)]
        grammar = intent_grammar(pack["intent_targets"])
        grammar_v3 = intent_grammar(pack["intent_targets"], "advance_time")
        wanted = set(args.variants.split(","))
        for case in [c for c in pack["cases"] if c["kind"] in args.kinds.split(",")]:
            if case["kind"] == "intent":
                variants = [v for v in [("v1", "intent", None), ("v2", "intent_v2", None),
                                        ("v2+grammar", "intent_v2", grammar),
                                        ("v3+grammar", "intent_v3", grammar_v3)] if v[0] in wanted]
            else:
                variants = [("", case["system"], None)]
            max_tokens = 80 if case["kind"] == "intent" else 160
            for variant, system, gbnf in variants:
                prompt = chatml(pack["systems"][system], case["user"], bos, think)
                for label, temp, seed in settings:
                    out, speed = run(model, prompt, temp, seed, max_tokens, gbnf)
                    sc = {"timeout": True} if out is None else score(case, out)
                    out = out or ""
                    results.append({"model": name, "case": case["id"], "kind": case["kind"], "variant": variant,
                                    "setting": label, "output": out, "speed": speed, **sc})
                    live.write(json.dumps(results[-1], ensure_ascii=False) + "\n")
                    live.flush()
                    print(f"{name:6} {case['id']:18} {variant:11} {label:9} {json.dumps(sc)}", file=sys.stderr, flush=True)

    live.close()
    json.dump(results, open(os.path.join(ROOT, f"spike/results/{stamp}.json"), "w"), indent=1, ensure_ascii=False)
    summarize(results)


def summarize(results):

    print("| Model | Narrations with an invented number | Required name missing | Degenerate | Intent correct: v1 prompt | v2 prompt | v2 + grammar | v3 + grammar | Prompt tok/s | Gen tok/s | Load s |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for name in dict.fromkeys(r["model"] for r in results):
        rs = [r for r in results if r["model"] == name]
        nar = [r for r in rs if r["kind"] == "narrate"]
        itn = {v: [r for r in rs if r["kind"] == "intent" and r["variant"] == v] for v in ("v1", "v2", "v2+grammar", "v3+grammar")}
        sp = [r["speed"] for r in rs if r["speed"]]
        avg = lambda k: sum(s[k] for s in sp) / len(sp) if sp else 0
        frac = lambda xs, f: f"{sum(1 for x in xs if f(x))}/{len(xs)}" if xs else "–"
        timeouts = sum(1 for r in rs if r.get("timeout"))
        if timeouts:
            print(f"<!-- {name}: {timeouts} generations timed out and count as failures -->")
        print(f"| {name} | {frac(nar, lambda r: r.get('timeout') or r['invented'])} | {frac(nar, lambda r: r.get('timeout') or not r['mentions'])} "
              f"| {frac(nar, lambda r: r.get('timeout') or r['degenerate'])} | {frac(itn['v1'], lambda r: r.get('correct', False))} "
              f"| {frac(itn['v2'], lambda r: r.get('correct', False))} | {frac(itn['v2+grammar'], lambda r: r.get('correct', False))} "
              f"| {frac(itn['v3+grammar'], lambda r: r.get('correct', False))} "
              f"| {avg('prompt_tps'):.0f} | {avg('gen_tps'):.0f} | {avg('load_s'):.2f} |")


if __name__ == "__main__":
    main()
