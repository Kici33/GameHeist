"""Original Brasslock effects. Optional authoring dependency: soundfile==0.13.1.

Run from any directory; checked-in OGG files keep the Java build self-contained.
"""
from array import array
import json
import math
from pathlib import Path
import random
import soundfile as sf

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "resource-pack/assets/gameheist"
RATE = 44100
CUES = {
    "carbine_fire": (.24, "Carbine fires", "Strzał z karabinka"),
    "carbine_reload": (1.9, "Carbine reloads", "Przeładowanie karabinka"),
    "alarm": (1.2, "Heist alarm", "Alarm napadu"),
    "drill_work": (.9, "Drill runs", "Praca wiertła"),
    "drill_jam": (.45, "Drill jams", "Zacięcie wiertła"),
    "drill_complete": (.65, "Vault opens", "Otwarcie skarbca"),
}


def synth(name, duration):
    rng = random.Random(name)
    samples = array("f")
    for i in range(round(duration * RATE)):
        t = i / RATE
        noise = rng.uniform(-1, 1)
        if name == "carbine_fire":
            value = .65 * noise * math.exp(-t * 38) + .35 * math.sin(2 * math.pi * 95 * t) * math.exp(-t * 23)
        elif name == "carbine_reload":
            value = 0
            for start, frequency in [(0, 950), (.48, 430), (1.65, 1300)]:
                age = t - start
                if age >= 0:
                    value += (.4 * noise + .18 * math.sin(2 * math.pi * frequency * age)) * math.exp(-age * 45)
        elif name == "alarm":
            value = .4 * math.sin(2 * math.pi * (650 * t + 35 / math.pi * (1 - math.cos(2 * math.pi * 2 * t))))
        elif name == "drill_work":
            value = (.22 * math.sin(2 * math.pi * 110 * t) + .1 * math.sin(2 * math.pi * 440 * t)
                     + .14 * noise) * (.8 + .2 * math.sin(2 * math.pi * 23 * t))
        elif name == "drill_jam":
            value = (.35 * math.sin(2 * math.pi * (230 * t - 200 * t * t)) + .2 * noise) * math.exp(-t * 7)
        else:
            value = sum(.2 * math.sin(2 * math.pi * f * t) * math.exp(-max(0, t - start) * 7)
                        for start, f in [(0, 660), (.16, 880), (.32, 1320)] if t >= start)
        samples.append(value * min(1, t / .003, (duration - t) / .025))
    return samples


def main():
    (ASSETS / "sounds").mkdir(parents=True, exist_ok=True)
    (ASSETS / "lang").mkdir(exist_ok=True)
    events, en, pl = {}, {}, {}
    for name, (duration, english, polish) in CUES.items():
        path = ASSETS / "sounds" / (name + ".ogg")
        with sf.SoundFile(path, "w", samplerate=RATE, channels=1, format="OGG", subtype="VORBIS") as out:
            out.buffer_write(synth(name, duration), dtype="float32")
        with sf.SoundFile(path) as recorded:
            decoded = array("f")
            decoded.frombytes(bytes(recorded.buffer_read(recorded.frames, dtype="float32")))
            peak = max(map(abs, decoded))
            rms = math.sqrt(sum(v * v for v in decoded) / len(decoded))
            assert recorded.channels == 1 and recorded.samplerate == RATE
            assert abs(len(decoded) / RATE - duration) < .01 and .005 < rms and peak < 1
            print(f"{name}: {len(decoded) / RATE:.2f}s mono, peak={peak:.3f}, RMS={rms:.3f}")
        subtitle = "subtitles.gameheist." + name
        events[name] = {"subtitle": subtitle, "sounds": [{"name": "gameheist:" + name, "attenuation_distance": 24}]}
        en[subtitle], pl[subtitle] = english, polish
    for path, content in [("sounds.json", events), ("lang/en_us.json", en), ("lang/pl_pl.json", pl)]:
        (ASSETS / path).write_text(json.dumps(content, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
