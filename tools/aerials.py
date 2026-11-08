#!/usr/bin/env python3
"""Download Apple TV aerials from Apple's servers and put them on the TV for the launcher's moving background.

The launcher plays them behind Home (Settings > Background to pick one, or cycle through them all). They are
1080p SDR HEVC files of 70 to 190 MB each, so the default set is about 1 GB on the TV.

Needs Python 3 and Google's adb (platform-tools) connected to the TV: `adb connect <TV IP>:5555`.

Usage:
    tools/aerials.py                      the default set (seven aerials)
    tools/aerials.py --list               every aerial Apple offers, by name
    tools/aerials.py "Hawaii" "London"    just these (names from --list)
    tools/aerials.py --serial 192.168.1.50:5555 ...   pick the TV when adb sees more than one device

Apple serves the aerials from sylvan.apple.com, whose certificate chains to an Apple root that common trust
stores leave out; the download therefore skips certificate checks for that host only. The files are Apple's
and stay on your TV; nothing is uploaded anywhere.
"""
import argparse
import io
import json
import os
import shutil
import ssl
import subprocess
import sys
import tarfile
import tempfile
import urllib.request

CATALOG = "https://sylvan.apple.com/Aerials/resources-16.tar"
PKG = "com.gathin.tvhome"
TV_DIR = f"/sdcard/Android/data/{PKG}/files/aerials"
DEFAULT = ["North America Aurora", "Hawaii", "New York Night", "London", "Korea and Japan Night",
           "Antarctica", "Dubai"]

_APPLE = ssl.create_default_context()
_APPLE.check_hostname = False
_APPLE.verify_mode = ssl.CERT_NONE


def fetch(url):
    return urllib.request.urlopen(url, context=_APPLE, timeout=60)


def catalog():
    """Apple's entries.json: {name: 1080p SDR URL}, names as Apple TV shows them. Places with several flights
    (Hawaii, London, Dubai...) get "Hawaii", "Hawaii 2", "Hawaii 3" in Apple's order."""
    data = fetch(CATALOG).read()
    with tarfile.open(fileobj=io.BytesIO(data)) as tar:
        entries = json.load(tar.extractfile("entries.json"))
    out = {}
    for a in entries.get("assets", []):
        url = a.get("url-1080-SDR") or a.get("url-1080-H264")
        name = (a.get("accessibilityLabel") or "").strip()
        if not url or not name:
            continue
        key, n = name, 1
        while key in out:
            n += 1
            key = f"{name} {n}"
        out[key] = url
    return out


def file_name(title):
    return "".join(c if c.isalnum() else "_" for c in title.lower()).strip("_") + ".mov"


def adb(serial, *args, capture=False):
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    return subprocess.run(cmd, check=True, text=True, capture_output=capture)


def download(url, dest):
    with fetch(url) as r, open(dest, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if total:
                print(f"\r    {done * 100 // total:3d}%  {done >> 20} of {total >> 20} MB", end="", flush=True)
    print()


def main():
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("names", nargs="*", help="aerial names (default: a set of seven)")
    p.add_argument("--list", action="store_true", help="print every aerial Apple offers")
    p.add_argument("--serial", help="adb device, e.g. 192.168.1.50:5555")
    args = p.parse_args()

    print("Reading Apple's aerial catalog...")
    cat = catalog()
    if args.list:
        for name in sorted(cat):
            print(name)
        return

    names = args.names or DEFAULT
    missing = [n for n in names if n not in cat]
    if missing:
        sys.exit("Not in Apple's catalog: " + ", ".join(missing) + "\nSee: tools/aerials.py --list")
    if shutil.which("adb") is None:
        sys.exit("adb not found: install Google's platform-tools and add it to PATH")

    adb(args.serial, "shell", f"mkdir -p {TV_DIR}")
    index = []
    with tempfile.TemporaryDirectory() as tmp:
        for n in names:
            f = file_name(n)
            print(f"{n}  ->  {f}")
            local = os.path.join(tmp, f)
            download(cat[n], local)
            adb(args.serial, "push", local, f"{TV_DIR}/{f}", capture=True)
            os.remove(local)   # one aerial on the computer at a time
            index.append({"file": f, "title": n})
        idx = os.path.join(tmp, "aerials.json")
        with open(idx, "w", encoding="utf-8") as fh:
            json.dump(index, fh, indent=2)
        adb(args.serial, "push", idx, f"{TV_DIR}/aerials.json", capture=True)
    adb(args.serial, "shell", f"am force-stop {PKG}; am start -n {PKG}/.MainActivity", capture=True)
    print(f"Done: {len(index)} aerials on the TV. On Home: Settings > Background.")


if __name__ == "__main__":
    main()
