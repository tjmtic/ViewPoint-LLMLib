# Spike findings — 2026-09-24

Three MiniCPM tiers, Starpoints' prompt pack (`prompt_pack.json`), run through the shim on an
M-series Mac CPU (`n_gpu_layers = 0`), models on the internal SSD. Each case greedy + three
seeds at temperature 0.7: 24 narrations and 16 intents per model per variant. Raw outputs:
`results/2026-09-24.json` (all variants v1–v2+grammar) and `results/2026-09-24-v3.json`.

| Tier | Invented numbers | Name missing | Intent: v1 | v2 | v2 + grammar | v3 + grammar | Prompt tok/s | Gen tok/s |
|---|---|---|---|---|---|---|---|---|
| MiniCPM4-0.5B QAT (253 MB) | 5/24 | 3/24 | 0/16 | 1/16 | 3/16 | 7/16 | ~1000 | ~160 |
| MiniCPM5-1B Q4_K_M (656 MB) | 0/24 | 1/24 | 3/16 | 12/16 | 11/16 | **16/16** | ~400 | ~90 (55 with grammar) |
| MiniCPM5-2B Q4_K_M (1.5 GB) | 0/24 | 1/24 | 15/16 | 16/16 | 16/16 | **16/16** | ~155 | ~44 (34 with grammar) |

Intent variants: **v1** first prompt; **v2** "you label, never perform" + three examples;
**v2 + grammar** the same with a GBNF grammar admitting only the schema and known targets;
**v3 + grammar** v2 with the action `show_time` renamed `advance_time`.

## What it means

1. **Drop the 0.5B tier.** It is the only tier that invents numbers (it put Polaris in Canis
   Major, gave the Pleiades 10000 stars) and it cannot do intents even under a grammar — it
   copies the "two hours" example into unrelated requests. Low-RAM devices get no LLM
   features rather than wrong ones.
2. **MiniCPM5-1B is a sound default** once prompts are built right: no invented numbers in 24
   narrations, 16/16 intents with v3 + grammar.
3. **The 2B is better, not required.** It got intents right even with the naive prompt and
   its narrations are the most faithful; offer it where RAM allows.
4. **Prompt design dominated model size for the 1B.** Two changes took it from 3/16 to 16/16:
   - Framing: "you label the request, the app performs it". With "turn a request into JSON"
     the model refuses ("I don't have the capability to show stars").
   - **Action names must not collide with request verbs.** Every seed mapped "show me Vega"
     to `show_time`; renaming it `advance_time` fixed all of them. Rule for Starpoints'
     intent schema: no action name that starts with a word users say ("show", "find me").
5. **Grammar = validity, not accuracy.** It never produced unparseable output and kept targets
   in the list, but did not raise the 1B's accuracy by itself (12 → 11 with v2). Use it for
   every intent call anyway: the app must never receive a half-object. It costs ~40% speed on
   short outputs (grammar checked against a 130K vocabulary) — fine for ~25-token intents.
6. **Narration errors the number check cannot see remain.** The 1B once invented "Orphans" for
   the Pleiades and called Andromeda "a distant cluster of stars"; greedy once read "light
   left the star in 2001" as "observed in 2001". Mitigations for the app: keep facts terse and
   unambiguous ("light left: 2001 years ago" beats a bare year), have the app speak the
   numbers itself (the model narrates around them), and prefer the 2B where available.

## Not covered here

Phone speed and memory (one real device per platform is the next step: the emulator here has
one core and 2 GB), Metal/GPU on iOS devices, thinking mode, multi-turn Q&A, and voice.
