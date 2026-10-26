#!/usr/bin/env python3
"""Bounded, opt-in Aoede audio capture. Included in the standalone Android sources."""

import argparse
import json
import os
import re
import selectors
import shutil
import subprocess
import time
from pathlib import Path

PACKAGES = {"com.aoede.twa": "aoede", "dev.vitreo.launcher": "vitreo", "me.kavishdevar.librepods": "librepods"}
PROPERTIES = (
    "ro.product.model",
    "ro.build.version.release",
    "ro.build.version.sdk",
    "ro.build.version.security_patch",
    "ro.build.id",
    "ro.build.version.incremental",
)


def safe_record(sample):
    if not isinstance(sample, dict) or not re.fullmatch(r"[a-z_]{1,48}", str(sample.get("event", ""))):
        return None
    result = {"event": sample["event"]}
    numeric = {
        "value",
        "wallMs",
        "monoMs",
        "jsWallMs",
        "jsMonoMs",
        "resource",
        "position",
        "ready",
        "network",
        "volume",
        "rate",
        "error",
        "decodedBytes",
        "generation",
        "liveTracks",
        "mode",
        "musicVolume",
        "probeDelayMs",
        "crossfade",
        "replayLevel",
        "fadeLevel",
        "volumeLevel",
        "contextId",
        "observedContextId",
    }
    boolean = {
        "paused",
        "ended",
        "muted",
        "blocked",
        "eventPlaying",
        "closing",
        "hasWebView",
        "serviceRunning",
        "islandVisible",
        "interactive",
        "keyguard",
        "globalMusicActive",
        "musicMuted",
        "wakeHeld",
        "replayGain",
    }
    for key in numeric | boolean:
        value = sample.get(key)
        if (
            key in boolean
            and isinstance(value, bool)
            or key in numeric
            and type(value) in (int, float)
            and -1e20 <= value <= 1e20
        ):
            result[key] = value
    for key, allowed in {
        "context": {"absent", "running", "suspended", "closed", "interrupted"},
        "observedContext": {"absent", "running", "suspended", "closed", "interrupted"},
        "playbackContext": {"absent", "running", "suspended", "closed", "interrupted"},
        "nativeState": {"none", "playing", "paused", "buffering", "error"},
        "visibility": {"hidden", "visible", "prerender", "unknown"},
    }.items():
        if isinstance(sample.get(key), str) and sample[key] in allowed:
            result[key] = sample[key]
    for key in ("availableOutputTypes", "globalContentTypes", "globalUsages"):
        if isinstance(sample.get(key), list):
            result[key] = [n for n in sample[key] if type(n) is int and 0 <= n < 256][:16]
    return result


def focus_snapshot(raw):
    """Keep focus ownership/loss, never client IDs, device names, addresses or full dumps."""
    result = []
    for line in raw.splitlines():
        package = re.search(r"\bpack:\s*([\w.]+)", line)
        if package:
            entry = {"owner": PACKAGES.get(package[1], "other")}
            for field in ("gain", "loss"):
                value = re.search(rf"\b{field}:\s*([A-Z_0-9-]+)", line)
                if value:
                    entry[field] = value[1][:40]
            result.append(entry)
    return result[:16]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--seconds", type=int, default=180)
    parser.add_argument("--output", type=Path, help="New directory; never overwrite a previous run")
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument(
        "--adb", default=shutil.which("adb") or str(Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb")
    )
    args = parser.parse_args()
    if args.self_test:
        assert focus_snapshot(
            "source: SECRET -- pack: com.aoede.twa -- client: PRIVATE -- gain: GAIN -- loss: LOSS_TRANSIENT"
        ) == [{"owner": "aoede", "gain": "GAIN", "loss": "LOSS_TRANSIENT"}]
        assert focus_snapshot("pack: private.package -- gain: GAIN") == [{"owner": "other", "gain": "GAIN"}]
        result = safe_record(
            {
                "event": "web_error",
                "url": "https://secret/?token=PRIVATE",
                "title": "PRIVATE",
                "position": 3,
                "volume": "PRIVATE",
                "nativeState": "https://secret/",
                "availableOutputTypes": [2, "PRIVATE"],
            }
        )
        assert result == {"event": "web_error", "position": 3, "availableOutputTypes": [2]}
        assert safe_record({"event": "https://secret/"}) is None
        print("PASS: audio capture redacts private focus clients and accepts only typed diagnostic fields.")
        return
    if args.output is None:
        parser.error("--output is required for capture")
    if not 1 <= args.seconds <= 600:
        parser.error("--seconds must be between 1 and 600")

    def adb(*command):
        try:
            run = subprocess.run([args.adb, *command], capture_output=True, text=True, timeout=10)
        except (OSError, subprocess.TimeoutExpired):
            raise SystemExit(
                "ADB unavailable or timed out; connect and authorize one device (or select ANDROID_SERIAL)."
            ) from None
        return run.stdout if run.returncode == 0 else ""

    pid = adb("shell", "pidof", "com.aoede.twa").strip().split()
    if len(pid) != 1 or not pid[0].isdigit():
        raise SystemExit(
            "Aoede is not running on an authorized device. Open it manually, then retry; no process was started or stopped."
        )
    args.output.mkdir(parents=True, exist_ok=False)
    environment = {
        "captured_wall_ms": round(time.time() * 1000),
        "airpods_model": "UNVERIFIED: confirm model/firmware in LibrePods",
        "grapheneos": "UNVERIFIED: confirm OS/build in Settings",
        "packages": {},
    }
    for prop in PROPERTIES:
        value = adb("shell", "getprop", prop).strip()
        environment[prop] = value[:120] if re.fullmatch(r"[\w .+()-]{1,120}", value) else "unavailable"
    for package, label in PACKAGES.items():
        raw = adb("shell", "dumpsys", "package", package)
        code = re.search(r"\bversionCode=(\d+)", raw)
        version = re.search(r"\bversionName=([\w.+()-]+)", raw)
        environment["packages"][label] = {
            "package": package,
            "version": version[1] if version else "unverified",
            "code": int(code[1]) if code else None,
        }
    raw = adb("shell", "dumpsys", "webviewupdate")
    provider = re.search(r"Current WebView package \(name, version\):\s*\(([\w.]+),\s*([\w.+()-]+)\)", raw)
    environment["webview"] = {"package": provider[1], "version": provider[2]} if provider else "unverified"
    (args.output / "environment.json").write_text(json.dumps(environment, indent=2) + "\n")
    previous = adb("shell", "getprop", "log.tag.AoedeAudio").strip()
    if previous not in {
        "",
        "V",
        "D",
        "I",
        "W",
        "E",
        "S",
        "VERBOSE",
        "DEBUG",
        "INFO",
        "WARN",
        "ERROR",
        "ASSERT",
        "SUPPRESS",
    }:
        raise SystemExit("Unexpected existing log tag configuration; left unchanged.")
    process = subprocess.Popen(
        [args.adb, "logcat", "--pid", pid[0], "-T", "1", "-v", "raw", "-s", "AoedeAudio:D", "*:S"],
        stdout=subprocess.PIPE,
        stderr=subprocess.DEVNULL,
    )
    os.set_blocking(process.stdout.fileno(), False)
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ)
    try:
        adb("shell", "setprop", "log.tag.AoedeAudio", "DEBUG")
        if adb("shell", "getprop", "log.tag.AoedeAudio").strip() != "DEBUG":
            raise SystemExit("Device refused the diagnostic log opt-in; no capture enabled.")
        start = time.monotonic()
        next_focus = start
        count = 0
        pending = b""
        with (args.output / "audio.jsonl").open("x") as log:
            while time.monotonic() - start < args.seconds and count < 600 and process.poll() is None:
                if time.monotonic() >= next_focus:
                    raw = adb("shell", "dumpsys", "audio")
                    log.write(
                        json.dumps(
                            {"event": "system_focus", "wallMs": round(time.time() * 1000), "focus": focus_snapshot(raw)}
                        )
                        + "\n"
                    )
                    next_focus = time.monotonic() + 5
                for key, _ in selector.select(timeout=0.5):
                    pending += os.read(key.fileobj.fileno(), 8192)
                    lines = pending.split(b"\n")
                    pending = lines.pop()[-8192:]
                    for line in lines:
                        try:
                            sample = safe_record(json.loads(line))
                        except (ValueError, UnicodeDecodeError):
                            continue
                        if sample and count < 600:
                            log.write(json.dumps(sample, separators=(",", ":")) + "\n")
                            count += 1
                log.flush()
    except KeyboardInterrupt:
        pass
    finally:
        try:
            adb("shell", "setprop", "log.tag.AoedeAudio", previous or '""')
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            selector.close()
    print(f"Capture saved in {args.output}; previous log setting restored. No audio recovery was attempted.")


if __name__ == "__main__":
    main()
