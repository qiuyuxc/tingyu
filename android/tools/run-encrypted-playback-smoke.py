#!/usr/bin/env python3
"""Check an APK's encrypted FLAC reads, seek adapter and FLAC recognition without audio output."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("container", type=Path)
    parser.add_argument("password_file", type=Path, help="Exact password bytes in UTF-8")
    parser.add_argument("plaintext_sha256")
    parser.add_argument("--android-jar", type=Path, required=True)
    parser.add_argument("--app-process", default="/system/bin/app_process")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    source = Path(__file__).with_name("EncryptedPlaybackSmoke.java")
    with tempfile.TemporaryDirectory(prefix="tingyu-encrypted-playback-") as directory:
        work = Path(directory)
        classes = work / "classes"
        classes.mkdir()
        subprocess.run(
            ["javac", "--release", "11", "-cp", str(args.android_jar), "-d", str(classes), str(source)],
            check=True, timeout=45,
        )
        probe = work / "probe.jar"
        subprocess.run(
            ["d8", "--min-api", "24", "--lib", str(args.android_jar), "--output", str(probe),
             *map(str, classes.glob("*.class"))],
            check=True, timeout=45,
        )
        apk = work / "app.apk"
        shutil.copy2(args.apk, apk)
        for file in (probe, apk):
            file.chmod(0o444)
        environment = os.environ.copy()
        environment["CLASSPATH"] = f"{probe}:{apk}"
        run = subprocess.run(
            [args.app_process, "-Xmx256m", "/system/bin", "EncryptedPlaybackSmoke", str(args.container.resolve()),
             str(args.password_file.resolve()), args.plaintext_sha256],
            env=environment, text=True, capture_output=True, timeout=180,
        )
        if run.returncode:
            print(run.stdout, end="")
            print(run.stderr, end="")
            raise SystemExit(run.returncode)
        result = json.loads(next(line for line in run.stdout.splitlines() if line.startswith("{") and '"passed":true' in line))
        result.update({
            "checked_at_utc": datetime.now(timezone.utc).isoformat(),
            "apk": args.apk.name,
            "apk_sha256": hashlib.sha256(args.apk.read_bytes()).hexdigest(),
            "container_sha256": hashlib.sha256(args.container.read_bytes()).hexdigest(),
            "audio_output_tested": False,
        })
        text = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(text)
        print(text, end="")


if __name__ == "__main__":
    main()
