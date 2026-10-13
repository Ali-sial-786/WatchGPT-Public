"""Synthetic smoke test, not a real-wrist accuracy benchmark.
Requires sherpa-onnx==1.13.8, soundfile and numpy; model directory is argv[1].
"""
from pathlib import Path
import sys
import numpy as np
import sherpa_onnx
import soundfile as sf

root = Path(__file__).resolve().parents[2]
model = Path(sys.argv[1])
spotter = sherpa_onnx.KeywordSpotter(
    tokens=str(model / 'tokens.txt'),
    encoder=str(next(model.glob('encoder-*.int8.onnx'))),
    decoder=str(next(model.glob('decoder-*.int8.onnx'))),
    joiner=str(next(model.glob('joiner-*.int8.onnx'))),
    keywords_file=str(root / 'wear/src/main/assets/kws/keywords.txt'),
    num_threads=1,
)
for name, expected in [('positive', True), ('negative', False), ('espeak-difficult', None)]:
    pcm, rate = sf.read(Path(__file__).parent / f'{name}.wav', dtype='float32')
    stream = spotter.create_stream()
    stream.accept_waveform(rate, np.concatenate([np.zeros(rate // 2, dtype=np.float32), pcm, np.zeros(rate, dtype=np.float32)]))
    found = []
    while spotter.is_ready(stream):
        spotter.decode_stream(stream)
        result = spotter.get_result(stream)
        if result:
            found.append(result)
            spotter.reset_stream(stream)
    print(f'{name}: {found}')
    if expected is not None:
        assert bool(found) == expected, f'{name}: unexpected detection result'
