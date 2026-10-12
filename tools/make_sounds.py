#!/usr/bin/env python3
"""Phase P / PA: turn the 19 chosen MP3s into the viewer's sound assets.

Java cannot decode MP3 and the viewer takes no dependencies, so this runs once, with ffmpeg,
and writes assets/sounds/*.wav (44100 Hz, 16-bit, stereo), manifest.json and CREDITS.md.
The viewer never needs ffmpeg.

    python tools\\make_sounds.py --check            list the 19 inputs, ffprobe them, write nothing
    python tools\\make_sounds.py                    check, then convert / loop / level / write everything
    python tools\\make_sounds.py --verify           re-read what is on disk and print the measurements
    options: --src DIR (default C:\\Users\\lenard\\Downloads)   --out DIR (default assets\\sounds)

Reads the MP3s only; never writes into --src. Writes only the 19 ids' WAVs, manifest.json and
CREDITS.md into --out, and deletes nothing. Stdlib only (subprocess, wave, array, json); needs
ffmpeg and ffprobe on PATH. The run is deterministic: running it twice gives identical files.

What is done to each file (PLAN.md, Phase P, PA2):
  * ffmpeg decodes to 44100 Hz stereo s16 (mono is duplicated, 24 and 48 kHz are resampled).
  * rain_heavy: the steadiest 120 s of the 847 s file (lowest variance of the 1 s RMS level in dB,
    on a 10 s grid) is cut out.
  * loops (rain_*, city_*, siren, tornado, hurricane): first the source's own fade-in and fade-out
    are cut off (3 s blocks must average within 3 dB of the file's median level, no second beyond 6 dB), otherwise the
    crossfade would blend two fades into a hole, or a loud end would swell once per loop; then an equal-power crossfade of the tail over the
    head (2 s, 0.5 s for the siren), so the last sample runs straight into the first. The exact
    cut is searched over the last 100 ms for the smallest step between last and first sample.
  * one-shots (thunder, cars): leading and trailing digital silence (below 1/1000 of full
    scale) trimmed, 20 ms fade out.
  * level: NOT baked in. manifest "gain" is a linear multiplier measured from the written WAV:
    beds -26 LUFS, siren -20 LUFS (ebur128 integrated loudness), one-shots peak -3 dBFS.
    Only if a file peaks above -0.5 dBFS (several MP3s overshoot 0 dBFS when decoded) is it scaled
    down in the WAV, to keep it clear of full scale. It is decoded as 32-bit float so the overshoot
    is not clipped first; the gain is measured afterwards, so it compensates.
  * cars: "peak" is the centre (seconds) of the loudest 50 ms window, for the director to land
    on the moment the car passes.
"""
import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import sys
import time
import wave
from array import array
from itertools import accumulate
from operator import mul
from pathlib import Path

RATE = 44100
ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SRC = Path(r"C:\Users\lenard\Downloads")
DEFAULT_OUT = ROOT / "assets" / "sounds"

SEAM_LIMIT = 0.04            # PA2: seam step below 4 percent of full scale
CEIL = int(32768 * 10 ** (-0.5 / 20))      # decoded peak above this is scaled down (-0.5 dBFS)
SILENCE = 32                 # 1/1000 of full scale
FADE_OUT = 0.020
HEAVY_SECONDS = 120.0
SEARCH = 4410                # 100 ms of candidate cut points for the seam
TOL_CHECK = 0.1              # PA1: duration tolerance in seconds
TOL_MANIFEST = 0.05          # PE1: duration vs manifest

# id, source stem (no .mp3), group, kind, seconds, Hz, channels
#   kind: bed (loop, -26 LUFS), siren (loop, -20 LUFS), shot (one-shot, peak -3 dBFS), car (shot + peak)
# seconds / Hz / channels are the PLAN.md table: what --check compares with the real file.
SOUNDS = [
    ("rain_heavy", "donrain-rain-110508", "rain", "bed", 846.9, 44100, 2),
    ("rain_light", "liecio-calming-rain-257596", "rain", "bed", 108.7, 44100, 2),
    ("thunder_1", "universfield-loud-thunder-192165", "thunder", "shot", 5.5, 44100, 2),
    ("thunder_2", "pwlpl-loud-thunder-sound-effect-359272", "thunder", "shot", 8.1, 44100, 2),
    ("thunder_3", "loswin23-thunder-601838", "thunder", "shot", 9.4, 48000, 2),
    ("thunder_4", "freesound_community-thunder-big-30291", "thunder", "shot", 16.1, 24000, 2),
    ("thunder_5", "freesound_community-big-thunder-clap-99753", "thunder", "shot", 11.5, 24000, 2),
    ("thunder_6", "universfield-thunder-strike-124463", "thunder", "shot", 11.1, 44100, 2),
    ("thunder_7", "u_q2hb2391vb-thunder-clap-521194", "thunder", "shot", 7.2, 44100, 2),
    ("car_1", "tanweraman-car-pass-by-337871", "traffic", "car", 3.4, 44100, 2),
    ("car_2", "soundque-car-passing-sound-soundque-field-recording-442774", "traffic", "car", 6.9, 48000, 2),
    ("car_3", "km007-car-pass-by-9111", "traffic", "car", 5.8, 44100, 2),
    ("car_4", "spinopel-car-passing-by-272055", "traffic", "car", 26.5, 44100, 1),
    ("car_5", "spinopel-passing-car-393073", "traffic", "car", 8.9, 44100, 1),
    ("city_a", "guillermoanaya-city-ambience-121693", "towns", "bed", 55.0, 48000, 2),
    ("city_b", "musicnation782-city-sounds-296780", "towns", "bed", 159.0, 44100, 2),
    ("siren", "pwlpl-tornado-warning-siren-sound-effect-359252", "storm", "siren", 8.1, 44100, 2),
    ("tornado", "freesound_community-tornado-sound-29406", "storm", "bed", 49.5, 24000, 2),
    ("hurricane", "juliush-hurricane-storm-nature-sounds-8397", "storm", "bed", 50.5, 44100, 2),
]
LOOP_FADE = {"bed": 2.0, "siren": 0.5}
LUFS_TARGET = {"bed": -26.0, "siren": -20.0}
SHOT_PEAK_DB = -3.0


class Fail(Exception):
    pass


# ---------------------------------------------------------------- ffmpeg / ffprobe

def find_tools():
    ff, fp = shutil.which("ffmpeg"), shutil.which("ffprobe")
    if not ff or not fp:
        sys.exit("make_sounds: ffmpeg and ffprobe must both be on PATH (found ffmpeg=%s, ffprobe=%s)" % (ff, fp))
    return ff, fp


def probe(ffprobe, path):
    r = subprocess.run([ffprobe, "-v", "error", "-select_streams", "a:0", "-show_entries",
                        "stream=sample_rate,channels:format=duration", "-of", "json", str(path)],
                       capture_output=True, text=True)
    if r.returncode != 0:
        raise Fail("ffprobe failed: " + (r.stderr.strip().splitlines() or ["no message"])[-1])
    j = json.loads(r.stdout)
    st = j["streams"][0]
    return float(j["format"]["duration"]), int(st["sample_rate"]), int(st["channels"])


def decode(ffmpeg, path, rate=RATE, channels=2, ss=None, t=None):
    """Decode to raw s16le through a pipe; returns array('h') (interleaved when stereo). Used for
    the level analysis only; the audio itself goes through decode_audio (float, no clipping)."""
    cmd = [ffmpeg, "-v", "error", "-nostdin", "-fflags", "+bitexact"]
    if ss is not None:
        cmd += ["-ss", "%.3f" % ss]
    cmd += ["-i", str(path)]
    if t is not None:
        cmd += ["-t", "%.3f" % t]
    cmd += ["-map_metadata", "-1", "-vn", "-ac", str(channels), "-ar", str(rate),
            "-acodec", "pcm_s16le", "-f", "s16le", "-"]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0 or not r.stdout:
        raise Fail("ffmpeg decode failed for %s: %s" % (path, r.stderr.decode("utf-8", "replace").strip()))
    a = array("h")
    a.frombytes(r.stdout[: len(r.stdout) // 2 * 2])
    if sys.byteorder != "little":
        a.byteswap()
    return a


def decode_audio(ffmpeg, path, ss=None, t=None):
    """Decode to 44100 Hz stereo as 32-bit float (so a decoder overshoot above 0 dBFS is kept, not
    clipped), then convert to 16-bit, scaled down first when the peak is above CEIL.
    Returns (array('h'), source_peak_dbfs, scale_factor)."""
    cmd = [ffmpeg, "-v", "error", "-nostdin", "-fflags", "+bitexact"]
    if ss is not None:
        cmd += ["-ss", "%.3f" % ss]
    cmd += ["-i", str(path)]
    if t is not None:
        cmd += ["-t", "%.3f" % t]
    cmd += ["-map_metadata", "-1", "-vn", "-ac", "2", "-ar", str(RATE), "-acodec", "pcm_f32le", "-f", "f32le", "-"]
    r = subprocess.run(cmd, capture_output=True)
    if r.returncode != 0 or not r.stdout:
        raise Fail("ffmpeg decode failed for %s: %s" % (path, r.stderr.decode("utf-8", "replace").strip()))
    f = array("f")
    f.frombytes(r.stdout[: len(r.stdout) // 8 * 8])
    if sys.byteorder != "little":
        f.byteswap()
    pk = max(max(f), -min(f))
    k = 32768.0
    if pk * 32768.0 > CEIL:
        k = CEIL / pk
    a = array("h", [int(round(v * k)) for v in f])
    return a, (20 * math.log10(pk) if pk > 0 else -120.0), k / 32768.0


def lufs(ffmpeg, wav):
    r = subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-nostats", "-i", str(wav),
                        "-af", "ebur128=framelog=quiet", "-f", "null", "-"], capture_output=True, text=True)
    m = re.findall(r"^\s*I:\s+(-?[\d.]+|-inf)\s+LUFS", r.stderr, re.M)
    if r.returncode != 0 or not m or m[-1] == "-inf":
        raise Fail("ebur128 gave no integrated loudness for %s" % wav)
    return float(m[-1])


# ---------------------------------------------------------------- PA1: check

def source_path(src, stem):
    return Path(src) / (stem + ".mp3")


def check(src, ffprobe):
    """PA1. Prints one line per chosen file; returns the number of problems."""
    src = Path(src)
    if not src.is_dir():
        sys.exit("make_sounds: source folder not found: %s" % src)
    bad = 0
    for sid, stem, group, kind, secs, hz, ch in SOUNDS:
        p = source_path(src, stem)
        if not p.is_file():
            print("MISSING  %-10s %s" % (sid, p.name))
            bad += 1
            continue
        try:
            dur, rate, chan = probe(ffprobe, p)
        except Fail as e:
            print("FAIL     %-10s %s: %s" % (sid, p.name, e))
            bad += 1
            continue
        why = []
        if abs(dur - secs) > TOL_CHECK:
            why.append("duration %.2f s, plan %.1f s" % (dur, secs))
        if rate != hz:
            why.append("%d Hz, plan %d" % (rate, hz))
        if chan != ch:
            why.append("%d ch, plan %d" % (chan, ch))
        if why:
            print("FAIL     %-10s %s: %s" % (sid, p.name, "; ".join(why)))
            bad += 1
        else:
            print("OK       %-10s %-62s %7.2f s %6d Hz %d ch" % (sid, p.name, dur, rate, chan))
    chosen = {stem.lower() + ".mp3" for _, stem, *_ in SOUNDS}
    others = sorted(n for n in os.listdir(src) if n.lower().endswith(".mp3") and n.lower() not in chosen)
    print("%d of %d inputs OK; %d other .mp3 file(s) in the folder ignored and left alone."
          % (len(SOUNDS) - bad, len(SOUNDS), len(others)))
    return bad


# ---------------------------------------------------------------- sample helpers

def peak_of(a):
    return max(max(a), -min(a)) if len(a) else 0


def energy(a):
    """Sum of squares of an array('h') slice, at C speed."""
    return sum(map(mul, a, a))


def clamp16(v):
    return -32768 if v < -32768 else 32767 if v > 32767 else v


def limit_peak(a):
    """Scale down when the peak is above CEIL. Returns (array, factor or 1.0)."""
    p = peak_of(a)
    if p <= CEIL:
        return a, 1.0
    f = CEIL / p
    return array("h", [int(round(v * f)) for v in a]), f


def trim_silence(a):
    n = len(a) // 2
    lo = 0
    while lo < n and abs(a[2 * lo]) <= SILENCE and abs(a[2 * lo + 1]) <= SILENCE:
        lo += 1
    hi = n
    while hi > lo and abs(a[2 * hi - 2]) <= SILENCE and abs(a[2 * hi - 1]) <= SILENCE:
        hi -= 1
    return array("h", a[2 * lo: 2 * hi])


def fade_out(a, seconds=FADE_OUT):
    n = len(a) // 2
    f = min(n, int(round(seconds * RATE)))
    for i in range(f):
        k = 1.0 - (i + 1) / f              # last frame ends at exactly 0
        j = 2 * (n - f + i)
        a[j] = int(round(a[j] * k))
        a[j + 1] = int(round(a[j + 1] * k))
    return a


def steady_core(a, drop_db=3.0, block=12):
    """Cut the source's own fade-in / fade-out and any swell at the ends. Returns (array, start_s,
    end_s). Levels are 1 s RMS (dB) every 250 ms; a 3 s block is steady when its mean is within
    drop_db of the median level and no second in it is more than twice that away. The core runs
    from the first steady block to the last, so a fade (which crosses the median level in about a
    second) or a sustained louder stretch at an end never counts."""
    n = len(a) // 2
    w = RATE // 4
    lin = [10 ** (v / 10) for v in window_db(a, 0, n, 0.25)]
    lvl = [10 * math.log10(max(1e-12, sum(lin[i:i + 4]) / 4)) for i in range(len(lin) - 3)]
    med = sorted(lvl)[len(lvl) // 2]
    good = []
    for i in range(len(lvl) - block + 1):
        seg = lvl[i:i + block]
        if abs(sum(seg) / block - med) <= drop_db and max(abs(v - med) for v in seg) <= 2 * drop_db:
            good.append(i)
    if not good:
        return a, 0.0, n / RATE
    first, last = good[0], good[-1] + block + 3     # block index -> 250 ms windows covered
    last = min(last, len(lin))
    return array("h", a[2 * first * w: 2 * last * w]), first * w / RATE, last * w / RATE


def make_loop(s, xfade_s, fixed_len=None):
    """Equal-power crossfade of the tail over the head.

    fixed_len given: output is exactly that many frames, cut from s[d:], d searched in the first
    100 ms. Otherwise the output is the file minus the crossfade, minus up to 100 ms searched.
    Either way the output's last frame is source frame w-1 and its first frame is source frame w
    (the crossfade starts at weight 0 for the head), so the join is one ordinary source step; w is
    chosen where that step is smallest. Returns (array, info).
    """
    n = len(s) // 2
    x = int(round(xfade_s * RATE))
    best = None
    for k in range(SEARCH):
        d, length = (k, fixed_len) if fixed_len else (0, n - x - k)
        w = d + length
        if length <= 2 * x or w + x > n:
            continue
        step = max(abs(s[2 * w] - s[2 * w - 2]), abs(s[2 * w + 1] - s[2 * w - 1]))
        if best is None or step < best[0]:
            best = (step, d, length)
    if best is None:
        raise Fail("not enough audio to crossfade %.1f s" % xfade_s)
    _, d, length = best
    w = d + length
    out = array("h", s[2 * d: 2 * (d + length)])
    for i in range(x):
        t = (i / x) * (math.pi / 2)
        a_in, a_out = math.sin(t), math.cos(t)
        for c in (0, 1):
            out[2 * i + c] = clamp16(int(round(s[2 * (d + i) + c] * a_in + s[2 * (w + i) + c] * a_out)))
    return out, {"cut": d / RATE, "tail_cut": (n - w - x) / RATE, "fade_frames": x}


def steadiest_window(ffmpeg, path, want, total):
    """Start (whole seconds, on a 10 s grid) of the window `want` seconds long whose 1 s RMS level
    (dB) varies least. Analysed on a mono 8 kHz decode."""
    a = decode(ffmpeg, path, rate=8000, channels=1)
    blk = 8000
    nb = len(a) // blk
    db = []
    for b in range(nb):
        e = energy(a[b * blk:(b + 1) * blk]) / blk
        db.append(10 * math.log10(e) if e > 1e-9 else -90.0)
    w = int(math.ceil(want))
    best = None
    for start in range(0, nb - w + 1, 10):
        seg = db[start:start + w]
        mean = sum(seg) / w
        var = sum((v - mean) ** 2 for v in seg) / w
        if best is None or var < best[0]:
            best = (var, start, mean)
    if best is None:
        raise Fail("source shorter than %d s" % w)
    return best[1], best[0], best[2]


def loudest_moment(a, win=0.05, hop=0.01):
    """Centre (s) of the loudest 50 ms window of the mono mix, hop 10 ms."""
    n = len(a) // 2
    cs = [0] + list(accumulate((a[2 * i] * a[2 * i] + a[2 * i + 1] * a[2 * i + 1]) for i in range(n)))
    w, h = int(win * RATE), int(hop * RATE)
    best, at = -1, 0
    for t in range(0, max(1, n - w + 1), h):
        e = cs[min(n, t + w)] - cs[t]
        if e > best:
            best, at = e, t
    return (at + w / 2) / RATE


def write_wav(path, a):
    tmp = Path(str(path) + ".tmp")
    with wave.open(str(tmp), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(a.tobytes() if sys.byteorder == "little" else bytes(a))
    os.replace(tmp, path)


def read_wav(path):
    with wave.open(str(path), "rb") as w:
        fmt = (w.getframerate(), w.getsampwidth() * 8, w.getnchannels())
        a = array("h")
        a.frombytes(w.readframes(w.getnframes()))
    return fmt, a


# ---------------------------------------------------------------- PA2: build

def build_one(ffmpeg, src, out, row, log):
    sid, stem, group, kind, secs, hz, ch = row
    p = source_path(src, stem)
    dest = Path(out) / (sid + ".wav")
    notes = []
    if sid == "rain_heavy":
        start, var, mean = steadiest_window(ffmpeg, p, HEAVY_SECONDS + LOOP_FADE["bed"], secs)
        notes.append("window starts %d s (1 s level sd %.2f dB, mean %.1f dBFS)" % (start, math.sqrt(var), mean - 90.31))
        s, src_pk, k = decode_audio(ffmpeg, p, ss=start, t=HEAVY_SECONDS + LOOP_FADE["bed"] + SEARCH / RATE + 0.05)
    else:
        s, src_pk, k = decode_audio(ffmpeg, p)
    full_len = len(s) // 2 / RATE
    notes.append("source peak %+.2f dBFS" % src_pk)
    if k != 1.0:
        notes.append("scaled by %.4f (%.2f dB) at decode" % (k, 20 * math.log10(k)))
    if kind in LOOP_FADE:
        if sid != "rain_heavy":
            s, c0, c1 = steady_core(s)
            notes.append("steady core %.2f-%.2f s of %.1f s" % (c0, c1, full_len))
        a, info = make_loop(s, LOOP_FADE[kind], int(HEAVY_SECONDS * RATE) if sid == "rain_heavy" else None)
        notes.append("loop start +%.3f s, end -%.3f s" % (info["cut"], info["tail_cut"]))
    else:
        a = trim_silence(s)
        notes.append("trimmed %.3f s -> %.3f s" % (len(s) / 2 / RATE, len(a) / 2 / RATE))
    a, f = limit_peak(a)
    if f != 1.0:
        notes.append("scaled again by %.4f after the crossfade" % f)
    if kind not in LOOP_FADE:
        fade_out(a)
    write_wav(dest, a)
    log("%-10s %s" % (sid, "; ".join(notes)))
    return dest, a


def entry_for(ffmpeg, row, a, wav):
    sid, stem, group, kind, secs, hz, ch = row
    if kind in LUFS_TARGET:
        gain = 10 ** ((LUFS_TARGET[kind] - lufs(ffmpeg, wav)) / 20)
    else:
        gain = 10 ** (SHOT_PEAK_DB / 20) / (peak_of(a) / 32768)
    e = {"file": sid + ".wav", "group": group, "seconds": round(len(a) / 2 / RATE, 3),
         "loop": kind in LOOP_FADE, "gain": round(gain, 4)}
    if kind == "car":
        e["peak"] = round(loudest_moment(a), 3)
    return e


def credits_text():
    rows = []
    for sid, stem, group, kind, *_ in SOUNDS:
        rows.append("| `%s` | %s.mp3 | %s |" % (sid, stem, stem.split("-")[0]))
    return "\n".join([
        "# Sound credits",
        "",
        "The WAV files in this folder were converted by `tools/make_sounds.py` from the 19 MP3 files",
        "below (44.1 kHz 16-bit stereo; the long beds cut to loops, the one-shots trimmed).",
        "",
        "The author is **only** the name before the first `-` in each file name. Nothing else about",
        "these files was checked. The file names look like Pixabay downloads; **licence not verified** -",
        "the user checks each file's page before this project is shared.",
        "",
        "| Id | Source file | Author (from the file name) |",
        "|---|---|---|",
        *rows,
        "",
    ])


def build(src, out, ffmpeg, ffprobe):
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    manifest = {}
    for row in SOUNDS:
        sid = row[0]
        wav, a = build_one(ffmpeg, src, out, row, lambda m: print("  " + m, flush=True))
        manifest[sid] = entry_for(ffmpeg, row, a, wav)
        print("  %-10s -> %s  %.3f s  gain %.4f%s" % (
            sid, wav.name, manifest[sid]["seconds"], manifest[sid]["gain"],
            "  peak@%.3f s" % manifest[sid]["peak"] if "peak" in manifest[sid] else ""), flush=True)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    (out / "CREDITS.md").write_text(credits_text(), encoding="utf-8", newline="\n")
    print("built %d files in %.0f s" % (len(manifest), time.time() - t0))


# ---------------------------------------------------------------- verify (reads only what is on disk)

def window_db(a, first_frame, frames, win=0.05):
    """RMS level (dBFS) of consecutive 50 ms windows, `frames` frames from `first_frame`."""
    w = int(win * RATE)
    out = []
    for f in range(first_frame, first_frame + frames - w + 1, w):
        seg = a[2 * f: 2 * (f + w)]
        e = energy(seg) / len(seg)
        out.append(10 * math.log10(e / 32768 ** 2) if e > 0 else -120.0)
    return out


def verify(out, ffmpeg):
    out = Path(out)
    problems = []
    mpath = out / "manifest.json"
    if not mpath.is_file():
        print("no manifest.json in %s" % out)
        return 1
    manifest = json.loads(mpath.read_text(encoding="utf-8"))
    ids = [r[0] for r in SOUNDS]
    if sorted(manifest) != sorted(ids):
        problems.append("manifest ids differ from the 19: extra %s missing %s" % (
            sorted(set(manifest) - set(ids)), sorted(set(ids) - set(manifest))))
    cred = (out / "CREDITS.md").read_text(encoding="utf-8") if (out / "CREDITS.md").is_file() else ""
    named = set(re.findall(r"[\w][\w .\-]*?\.mp3", cred))
    want = {stem + ".mp3" for _, stem, *_ in SOUNDS}
    if named != want:
        problems.append("CREDITS.md names %d files; extra %s missing %s" % (len(named), sorted(named - want), sorted(want - named)))
    stray = sorted(n for n in os.listdir(out) if n.lower().endswith(".wav") and n[:-4] not in ids)
    if stray:
        problems.append("stray .wav in the folder: %s" % stray)

    hdr = "%-10s %9s %9s %8s %5s %7s %8s %8s %7s %7s %14s  %s" % (
        "id", "bytes", "seconds", "peak dB", "clip", "gain", "seam %", "mean|d|%", "xf dip", "xf bump", "outside range", "sha256")
    print(hdr)
    total = 0
    for sid, stem, group, kind, *_ in SOUNDS:
        wav = out / (sid + ".wav")
        if not wav.is_file():
            problems.append(sid + ": wav missing")
            continue
        size = wav.stat().st_size
        total += size
        (rate, bits, chans), a = read_wav(wav)
        n = len(a) // 2
        sec = n / RATE
        if (rate, bits, chans) != (RATE, 16, 2):
            problems.append("%s: format %s" % (sid, (rate, bits, chans)))
        pk = peak_of(a)
        pk_db = 20 * math.log10(pk / 32768) if pk else -120.0
        clipped = a.count(32767) + a.count(-32768)
        if clipped or pk >= 32767:
            problems.append("%s: clips (%d samples at full scale)" % (sid, clipped))
        m = manifest.get(sid, {})
        if abs(sec - m.get("seconds", -9)) > TOL_MANIFEST:
            problems.append("%s: %.3f s vs manifest %s" % (sid, sec, m.get("seconds")))
        seam = mean_d = dip = bump = rng = ""
        if kind in LOOP_FADE:
            if m.get("loop") is not True:
                problems.append(sid + ": manifest loop is not true")
            step = max(abs(a[0] - a[-2]), abs(a[1] - a[-1])) / 32768
            seam = "%.3f" % (step * 100)
            mean_d = "%.3f" % (sum(abs(a[i] - a[i - 2]) for i in range(2, min(len(a), 400002))) / (min(len(a), 400002) - 2) / 32768 * 100)
            if step >= SEAM_LIMIT:
                problems.append("%s: seam step %.2f%% >= 4%%" % (sid, step * 100))
            xf = int(round(LOOP_FADE[kind] * RATE))
            inside = window_db(a, 0, xf)
            body = window_db(a, xf, n - xf)
            body_sorted = sorted(body)
            med = body_sorted[len(body_sorted) // 2]
            dip, bump = "%.1f" % (min(inside) - med), "%.1f" % (max(inside) - med)
            rng = "%.1f..%.1f" % (body_sorted[0] - med, body_sorted[-1] - med)
        elif m.get("loop") is not False:
            problems.append(sid + ": manifest loop is not false")
        if sid == "rain_heavy" and n != int(HEAVY_SECONDS * RATE):
            problems.append("rain_heavy is %d frames, not 120 s" % n)
        if kind == "car" and "peak" not in m:
            problems.append(sid + ": car without peak")
        if kind != "car" and "peak" in m:
            problems.append(sid + ": unexpected peak key")
        print("%-10s %9d %9.3f %8.2f %5d %7s %8s %8s %7s %7s %14s  %s" % (
            sid, size, sec, pk_db, clipped, m.get("gain", "?"), seam, mean_d, dip, bump, rng,
            hashlib.sha256(wav.read_bytes()).hexdigest()[:12]))
    print("total %d bytes = %.1f MB (10^6) = %.1f MiB across %d wav files" % (
        total, total / 1e6, total / 2 ** 20, len(SOUNDS)))
    print("seam % = |last - first| of the wav (the step a looping player makes); mean|d|% = mean adjacent-sample step"
          " over the first 9 s, for scale; xf dip / bump = lowest / highest 50 ms RMS level inside the crossfade"
          " region against the median of the rest (dB); outside range = the same spread outside it.")
    if problems:
        print("\nPROBLEMS:")
        for p in problems:
            print("  - " + p)
        return 1
    print("verify: all %d wavs open with wave as 44100 Hz / 16-bit / stereo; no clipping; every loop seam < 4%%; manifest and credits match." % len(ids))
    return 0


# ---------------------------------------------------------------- main

def main(argv=None):
    ap = argparse.ArgumentParser(description="Convert the 19 chosen MP3s into the viewer's sound assets.")
    ap.add_argument("--src", default=str(DEFAULT_SRC), help="folder holding the MP3s (read only)")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="where the WAVs, manifest.json and CREDITS.md go")
    ap.add_argument("--check", action="store_true", help="only list and ffprobe the 19 inputs; write nothing")
    ap.add_argument("--verify", action="store_true", help="only re-read --out and print the measurements")
    args = ap.parse_args(argv)
    ffmpeg, ffprobe = find_tools()
    if args.verify:
        return verify(args.out, ffmpeg)
    bad = check(args.src, ffprobe)
    if args.check:
        return 1 if bad else 0
    if bad:
        print("make_sounds: fix the inputs above first; nothing was written.")
        return 1
    try:
        build(args.src, args.out, ffmpeg, ffprobe)
    except Fail as e:
        print("make_sounds: " + str(e))
        return 1
    return verify(args.out, ffmpeg)


if __name__ == "__main__":
    sys.exit(main())
