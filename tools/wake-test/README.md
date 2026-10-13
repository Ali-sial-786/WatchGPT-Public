# Synthetic wake smoke test

Run `python tools/wake-test/check.py /path/to/extracted/sherpa-model` with Python packages `sherpa-onnx==1.13.8`, `numpy`, and `soundfile`. The build's generated `wear/build/generated/wakeModel/kws` is also suitable.

Fixtures contain synthesized words, no user recordings. `positive.wav` says “Hey watch G P T”; `negative.wav` says “Please remind me tomorrow morning to drink water.” They were synthesized locally with Piper's en_US-lessac-medium voice (model https://huggingface.co/rhasspy/piper-voices/tree/main/en/en_US/lessac/medium ; model card and its applicable license govern the model, which is not redistributed). `espeak-difficult.wav` uses eSpeak NG to say “Hey watch gee pee tee.”

The positive Piper fixture triggered `HEY_WATCHGPT`; the negative did not. The eSpeak fixture **did not trigger**: it is retained as a documented recognition limitation, not disguised as a passing positive. This tiny synthetic check cannot establish accent coverage, real microphone behavior, battery impact, false-accept rate, or accuracy on a Watch9. Use the watch's Learn my voice flow; tile and double press remain dependable activation paths if wake recognition misses.

Tokens were generated with the upstream model's SentencePiece `bpe.model` for HEY WATCH G P T, HEY WATCH GPT, and HEY WATCH GEE PEE TEE; each token was verified against `tokens.txt`. Original acoustic models and thresholds were preserved. Voice training now retains up to five words so it does not truncate GPT's letters.
