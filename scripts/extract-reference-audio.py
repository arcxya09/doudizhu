#!/usr/bin/env python3
"""Build offline audio assets from the user-supplied 16057.mp4 recording.

The source recording is NOT committed. Requires ffmpeg, NumPy and SciPy.
BGM is an instrumental excerpt, not a claimed recovery of the full master.
"""
import argparse
import json
import subprocess
import tempfile
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.ndimage import median_filter, gaussian_filter
from scipy.signal import stft, istft

RATE = 22050
# Bounds follow the actual game events visible in the supplied screen recording.
CUES = {
    'select': (0.915, 1.105),
    'play': (1.980, 2.170),
    'bid': (7.825, 8.665),
    'bid_pass': (0.915, 1.105),
    'pass': (26.610, 27.340),
    'pair_k': (29.520, 30.460),
    'pair_a': (31.590, 32.570),
    'pair_2': (35.135, 36.000),
    'airplane': (23.820, 26.190),
    'bomb': (40.710, 43.390),
    'rocket': (46.560, 49.420),
    'win': (51.660, 54.170),
}


def pcm(path, data):
    wavfile.write(str(path), RATE,
                  np.rint(np.clip(data, -.98, .98) * 32767).astype(np.int16))


def fade(data, seconds=.008):
    out = data.copy()
    n = min(int(RATE * seconds), len(out) // 2)
    out[:n] *= np.linspace(0, 1, n)
    out[-n:] *= np.linspace(1, 0, n)
    return out


def foreground(samples):
    # REPET-style nonlocal median: similarly sounding but temporally separated
    # frames estimate the repeating accompaniment. Only the residual is used
    # for the short action cues; BGM never uses this uncertain separation.
    _, _, z = stft(samples, RATE, nperseg=2048, noverlap=1792)
    mag = np.abs(z)
    features = np.log1p(mag * 800)
    features /= np.maximum(features.mean(axis=1, keepdims=True), .06)
    features /= np.maximum(np.linalg.norm(features, axis=0, keepdims=True), 1e-9)
    features = features.astype(np.float32)
    background = np.zeros_like(mag)
    times = np.arange(mag.shape[1])
    exclusion = int(1.5 * RATE / 256)
    for start in range(0, mag.shape[1], 96):
        end = min(start + 96, mag.shape[1])
        similarity = features[:, start:end].T @ features
        similarity[np.abs(times[None, :] - times[start:end, None]) < exclusion] = -2
        nearest = np.argpartition(similarity, -24, axis=1)[:, -24:]
        for row, neighbors in enumerate(nearest):
            background[:, start + row] = np.median(mag[:, neighbors], axis=1)
    foreground_mag = np.maximum(mag - 1.55 * background, 0)
    mask = foreground_mag ** 2 / (foreground_mag ** 2 + background ** 2 + 1e-12)
    mask = median_filter(mask, size=(3, 3))
    _, separated = istft(z * mask, RATE, nperseg=2048, noverlap=1792)
    return separated[:len(samples)]


def recover_music(samples):
    """Recover eight bars; keep safe intervals and suppress mixed-in foreground."""
    _, times, z = stft(samples, RATE, nperseg=2048, noverlap=1792)
    mag = np.abs(z)
    clean_intervals = [(2.12, 7.74), (8.76, 10.5), (30.53, 31.43),
                       (32.75, 34.3), (36.3, 37.4)]
    allowed = np.zeros(len(times), bool)
    for start, end in clean_intervals:
        allowed |= (times >= start) & (times <= end)
    # Only no-action reference intervals enter the accompaniment dictionary.
    features = np.log1p(mag * 800)
    features /= np.maximum(features.mean(axis=1, keepdims=True), .06)
    features /= np.maximum(np.linalg.norm(features, axis=0, keepdims=True), 1e-9)
    features = features.astype(np.float32)
    reference_ids = np.flatnonzero(allowed)
    background = np.zeros_like(mag)
    for start in range(0, len(times), 96):
        end = min(start + 96, len(times))
        similarity = features[:, start:end].T @ features[:, reference_ids]
        similarity[np.abs(times[start:end, None] - times[reference_ids]) < 1] = -2
        nearest = np.argpartition(similarity, -16, axis=1)[:, -16:]
        for row, neighbors in enumerate(nearest):
            background[:, start + row] = np.median(mag[:, reference_ids[neighbors]], axis=1)
    background = np.minimum(mag, background)
    foreground_mag = np.maximum(mag - background, 0)
    mask = background ** 2 / (background ** 2 + (foreground_mag * 4) ** 2 + 1e-12)
    mask = gaussian_filter(median_filter(mask, size=(3, 3)), sigma=(.55, .45))
    # The same accompaniment recurs after 30.628571 seconds. Its phase
    # coherence adds evidence where either recording includes an action.
    offset = round(30.6285714286 * RATE)
    _, _, repeated = stft(samples[offset:], RATE, nperseg=2048, noverlap=1792)
    n = min(z.shape[1], repeated.shape[1], int(10.5 * RATE / 256))
    first, second = z[:, :n], repeated[:, :n]
    cross = gaussian_filter((first * np.conj(second)).real, sigma=(1, 2))
    cross = cross + 1j * gaussian_filter((first * np.conj(second)).imag, sigma=(1, 2))
    power_first = gaussian_filter(np.abs(first) ** 2, sigma=(1, 2))
    power_second = gaussian_filter(np.abs(second) ** 2, sigma=(1, 2))
    coherence = np.clip(np.abs(cross) ** 2 / (power_first * power_second + 1e-12), 0, 1)
    paired = np.clip(np.abs(second) / (np.abs(first) + 1e-8), 0, 1) * coherence ** 2
    mask[:, :n] = np.maximum(mask[:, :n], paired * .85)
    mask = np.minimum(mask, .9 * background / (mag + 1e-9))
    clean_weight = gaussian_filter(allowed.astype(float), sigma=3)
    mask = mask * (1 - clean_weight[None, :]) + clean_weight[None, :]
    _, recovered = istft(z * mask, RATE, nperseg=2048, noverlap=1792)
    recovered = recovered[:len(samples)]
    begin = round(2.12 * RATE)
    count = round(1920 / 115 * RATE)  # eight bars, ~115 BPM
    overlap = round(.12 * RATE)
    excerpt = recovered[begin:begin + count + overlap].copy()
    loop = excerpt[:count].copy()
    blend = np.linspace(0, 1, overlap, endpoint=False)
    loop[:overlap] = excerpt[count:count + overlap] * (1 - blend) + excerpt[:overlap] * blend
    rms_envelope = np.sqrt(gaussian_filter(loop ** 2, sigma=RATE * .12))
    loop *= np.clip(.007 / (rms_envelope + .001), .8, 1.4)
    loop *= min(.12 / max(np.sqrt(np.mean(loop ** 2)), 1e-9), .88 / max(np.abs(loop)))
    attenuation = []
    for start, end in [(7.75, 8.76), (10.5, 11.6), (12, 14.4), (14.4, 15.5), (16.85, 18.95)]:
        original = samples[int(start * RATE):int(end * RATE)]
        processed = recovered[int(start * RATE):int(end * RATE)]
        attenuation.append({'source_seconds': [start, end],
            'rms_reduction_db': float(-20 * np.log10(np.sqrt(np.mean(processed ** 2)) /
                                                    np.sqrt(np.mean(original ** 2))))})
    report = {'source_start_seconds': 2.12, 'loop_seconds': len(loop) / RATE,
              'crossfade_seconds': .12, 'full_track': False,
              'method': 'no-action reference spectra, repeated-section coherence, foreground mask',
              'peak': float(np.abs(loop).max()), 'rms': float(np.sqrt(np.mean(loop ** 2))),
              'boundary_jump': float(abs(loop[0] - loop[-1])),
              'adjacent_sample_jump_p99': float(np.quantile(np.abs(np.diff(loop)), .99)),
              'mixed_event_window_attenuation': attenuation,
              'listening_review': False}
    return loop, report


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('recording', type=Path)
    parser.add_argument('--output', type=Path,
                        default=Path('app/src/main/assets/audio'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        wave = Path(tmp) / 'source.wav'
        subprocess.run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
                        '-i', str(args.recording), '-vn', '-ar', str(RATE),
                        '-ac', '1', '-c:a', 'pcm_s16le', str(wave)], check=True)
        rate, data = wavfile.read(wave)
        samples = data.astype(np.float64) / 32768
    assert rate == RATE
    loop, music_report = recover_music(samples)
    pcm(args.output / 'table_loop.wav', loop)
    separated = foreground(samples)
    report = {'sample_rate': RATE, 'bgm': music_report, 'cues': {}}
    for name, (start, end) in CUES.items():
        clip = fade(separated[int(start * RATE):int(end * RATE)])
        # Keep cue loudness consistent, with headroom for simultaneous music.
        rms = max(np.sqrt(np.mean(clip ** 2)), 1e-6)
        peak = max(np.max(np.abs(clip)), 1e-6)
        gain = min(.12 / rms, .65 / peak, 12)
        clip *= gain
        pcm(args.output / f'{name}.wav', clip)
        report['cues'][name] = {'source_seconds': [start, end],
                               'duration_seconds': len(clip) / RATE,
                               'peak': float(np.abs(clip).max()),
                               'rms': float(np.sqrt(np.mean(clip ** 2)))}
    (args.output / 'provenance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
