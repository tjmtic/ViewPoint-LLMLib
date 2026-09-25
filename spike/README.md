# Desktop spike: which model tier, which prompts

Runs `prompt_pack.json` — Starpoints' real tasks — through the shim itself
(`build/host/cmake/lm_generate`, built by `scripts/host-test.sh`), so what is measured is what
the apps will run. Every case is run greedy and at temperature 0.7 with three seeds.

```bash
spike/run_spike.py "1B=/path/MiniCPM5-1B-Q4_K_M.gguf:bos=<s>:think=off" \
                   "0.5B=/path/MiniCPM4-0.5B-QAT-Int4_gptq_aware_q4_0.gguf:think=none"
```

`bos=` is the literal start token the template writes (MiniCPM5: `<s>`; MiniCPM4's tokenizer
adds its own, so none). `think=off` renders MiniCPM5's empty think block; `none` omits it.

## What is scored

| Kind | Measure | Why |
|---|---|---|
| narrate | any number in the answer that is not in the facts | the app owns every number; an invented one is a wrong fact |
| narrate | the object's name is missing | catches the model renaming things ("Orphans" for the Pleiades) |
| narrate | empty or looping output | the missing-`<s>` failure mode |
| intent | action, target and minutes all correct | the only thing the app acts on |

Intents run three ways: the first prompt (`intent`), a labeling prompt with three examples
(`intent_v2`, examples deliberately not the test requests), and `intent_v2` plus a GBNF
grammar that only admits the schema with known targets (`Sampling.grammar` in the library).

Not scored automatically, so read the outputs in `results/<date>.json`: wrong facts without
numbers ("a distant cluster of stars" for a galaxy) and misread facts ("observed in 2001" for
"light left the star in 2001").
