#!/usr/bin/env python3
"""Copy the ignored .env key into the debug app's private storage over ADB."""
import argparse
from pathlib import Path
import shlex
import shutil
import subprocess


def read_key(env_file: Path) -> str:
    for line in env_file.read_text().splitlines():
        line = line.strip()
        if line.startswith("export "):
            line = line[7:]
        name, separator, value = line.partition("=")
        if separator and name.strip() == "FISH_AUDIO_KEY":
            parts = shlex.split(value, comments=True)
            if len(parts) == 1 and parts[0]:
                return parts[0]
            raise SystemExit("FISH_AUDIO_KEY must contain one non-empty value.")
    raise SystemExit("FISH_AUDIO_KEY is missing from .env.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Intended device serial from adb devices")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    key = read_key(root / ".env")
    adb = shutil.which("adb") or str(Path.home() / "Library/Android/sdk/platform-tools/adb")
    # Secret travels through stdin, never command arguments, build inputs, or logs.
    subprocess.run(
        [adb, "-s", args.serial, "shell", "run-as dev.backbutton sh -c 'umask 077; mkdir -p files; cat > files/fish-audio-key'"],
        input=key.encode(), check=True, stdout=subprocess.DEVNULL,
    )
    print("Fish Audio key configured in the phone's app-private storage.")


if __name__ == "__main__":
    main()
