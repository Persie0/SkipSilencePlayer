#!/usr/bin/env python3
import math
import struct
import sys
import wave

path = sys.argv[1]
mode = sys.argv[2] if len(sys.argv) > 2 else "digital"
rate = 44100
segments = [
    ("tone", 1.0),
    ("silence", 1.2),
    ("tone", 1.0),
    ("silence", 1.3),
    ("tone", 1.5),
]

with wave.open(path, "wb") as out:
    out.setnchannels(1)
    out.setsampwidth(2)
    out.setframerate(rate)

    phase = 0
    for kind, seconds in segments:
        frames = int(rate * seconds)
        for _ in range(frames):
            if mode == "quiet-speech":
                sample = int(0.003 * 32767 * math.sin(2 * math.pi * 440 * phase / rate))
            elif kind == "tone":
                sample = int(0.35 * 32767 * math.sin(2 * math.pi * 440 * phase / rate))
            elif mode == "noisy":
                # -37 dBFS RMS background hum: fixed -48 dB cannot detect it.
                sample = int(0.02 * 32767 * math.sin(2 * math.pi * 120 * phase / rate))
            else:
                sample = 0
            out.writeframesraw(struct.pack("<h", sample))
            phase += 1
