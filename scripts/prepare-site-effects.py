#!/usr/bin/env python3
"""Prepare five short gameplay effects from local downloads of the supplied pages.

Requires ffmpeg and NumPy. No network requests and no background music import.
Source files are explicitly supplied; the site's music is not redistributed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import wave

import numpy as np

RATE = 22050
SOURCES = {
    "deal": ("deal.mp3", "151001074680", "欢乐斗地主洗牌音效", "201509/6407.mp3"),
    "bid": ("bid.wav", "150929492982", "叫地主音效", "201509/6401.wav"),
    "cannot_beat": ("pass.wav", "170809479030", "斗地主要不起音效", "201708/9039.wav"),
    "rocket": ("rocket.mp3", "150923301382", "欢乐斗地主王炸音效", "201509/6377.mp3"),
    "airplane": ("airplane.wav", "150204155871", "欢乐斗地主飞机音效", "201502/5468.wav"),
}


def prepare(source, destination):
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(source), "-ac", "1",
                          "-ar", str(RATE), "-f", "f32le", "pipe:1"],
                         check=True, capture_output=True).stdout
    data = np.frombuffer(raw, dtype="<f4").astype(np.float64)
    if not len(data) or not np.isfinite(data).all():
        raise ValueError(f"Invalid audio: {source}")
    # Remove long leading/trailing silence, retaining 20 ms before the event and
    # 45 ms after it. Leave the entire spoken phrase or effect intact.
    peak = float(np.abs(data).max())
    active = np.flatnonzero(np.abs(data) > max(0.002, peak * 0.005))
    if not len(active):
        raise ValueError(f"Silent effect: {source}")
    begin = max(0, int(active[0]) - int(.020 * RATE))
    end = min(len(data), int(active[-1]) + int(.045 * RATE) + 1)
    clip = data[begin:end].copy()
    rms = float(np.sqrt(np.mean(clip ** 2)))
    gain = min(.13 / max(rms, 1e-9), .82 / max(np.abs(clip).max(), 1e-9), 4.0)
    clip *= gain
    fade = min(int(.005 * RATE), len(clip) // 2)
    clip[:fade] *= np.linspace(0, 1, fade)
    clip[-fade:] *= np.linspace(1, 0, fade)
    pcm = np.rint(clip * 32767).astype("<i2")
    with wave.open(str(destination), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(RATE)
        out.writeframes(pcm.tobytes())
    return {"source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
            "source_duration_seconds": len(data) / RATE,
            "retained_source_seconds": [begin / RATE, end / RATE],
            "duration_ms": round(len(clip) * 1000 / RATE),
            "peak": float(np.abs(clip).max()), "gain": float(gain),
            "output_sha256": hashlib.sha256(destination.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source_directory", type=Path)
    parser.add_argument("--output", type=Path, default=Path("app/src/main/assets/audio"))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report = {"format": "22050 Hz mono 16-bit PCM WAV",
              "source_catalog": "https://m.sc.chinaz.com/tag_yinxiao/doudizhu.html",
              "site_notice": "仅供学习与参考，请勿用于商业用途",
              "background_music_included": False, "effects": {}}
    for key, (filename, page, title, audio) in SOURCES.items():
        entry = prepare(args.source_directory / filename, args.output / (key + ".wav"))
        entry.update(title=title, page="https://m.sc.chinaz.com/yinxiao/" + page + ".html",
                     downloaded_from="https://downsc.chinaz.net/Files/DownLoad/sound1/" + audio)
        report["effects"][key] = entry
    (args.output / "site_provenance.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    combined_path = args.output / "provenance.json"
    if combined_path.exists():
        combined = json.loads(combined_path.read_text(encoding="utf-8"))
        for key, value in report["effects"].items():
            combined.setdefault("cues", {})[key] = dict(value, source_type="website_short_effect")
        combined_path.write_text(json.dumps(combined, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: value["duration_ms"] for key, value in report["effects"].items()}))


if __name__ == "__main__":
    main()
