"""Automated tests for tools/publish_release.sh.

Verifies:
1. First-release creation: tag without existing release creates release with asset and notes.
2. SHA-256 byte-for-byte verification: compares local cryptographic hash with remote asset.
3. Existing-release handling: tag with existing matching asset preserves asset without re-upload/clobber.
4. Protection of existing valid releases: conflicting asset on published release is not overwritten.
5. Recovery from interrupted publications: draft releases are repaired and published (--draft=false).
6. Non-confusion of auth/network failures: 401/403 or network drops are never confused with 404 (no create fallback).
7. Upload failure resilience: failed upload does NOT fallback to create or mask the failure.
8. Post-verification gates: rejects release if draft, missing asset, mismatched SHA-256, or multiple APKs.
9. Missing / empty / oversized artifact guard: fails fast before invoking gh CLI.

Run from repository root: python3 -m unittest discover -s tests -v
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

FAKE_GH_SCRIPT = r'''
import hashlib
import json
import os
import shutil
import sys
import time

STATE_PATH = os.environ["FAKE_GH_STATE"]
LOCK_FILE = STATE_PATH + ".lock"

def acquire_lock():
    for _ in range(500):
        try:
            fd = os.open(LOCK_FILE, os.O_CREAT | os.O_EXCL | os.O_RDWR)
            return fd
        except (FileExistsError, OSError):
            time.sleep(0.01)
    return None

def release_lock(fd):
    if fd is not None:
        try:
            os.close(fd)
            if os.path.exists(LOCK_FILE):
                os.unlink(LOCK_FILE)
        except OSError:
            pass

def load():
    with open(STATE_PATH, "r", encoding="utf-8") as f:
        return json.load(f)

def save(state):
    tmp = STATE_PATH + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2)
    os.replace(tmp, STATE_PATH)

def next_asset_id(state):
    state["next_asset_id"] = state.get("next_asset_id", 1)
    asset_id = state["next_asset_id"]
    state["next_asset_id"] = asset_id + 1
    return str(asset_id)

def make_asset(state, name, content, asset_state="uploaded"):
    """Build an asset the way GitHub reports it: id, apiUrl, digest and state."""
    sha = hashlib.sha256(content).hexdigest()
    asset_id = next_asset_id(state)
    return {
        "name": name,
        "size": len(content),
        "sha256": sha,
        "digest": "sha256:" + sha,
        "state": asset_state,
        "id": asset_id,
        "apiUrl": "https://api.github.com/repos/Flexy01/tvhome-launcher/releases/assets/" + asset_id,
    }

def normalize(state):
    """Give every pre-seeded asset the identity fields GitHub reports."""
    for rel in state.get("releases", {}).values():
        for a in rel.get("assets", []):
            if not a.get("id"):
                a["id"] = next_asset_id(state)
            if not a.get("apiUrl"):
                a["apiUrl"] = "https://api.github.com/repos/Flexy01/tvhome-launcher/releases/assets/" + a["id"]
            if "state" not in a:
                a["state"] = "uploaded"
            if "digest" not in a:
                digest = a.get("sha256")
                a["digest"] = ("sha256:" + digest) if digest else None
    return state

lock_fd = acquire_lock()
try:
    state = normalize(load())
    state["calls"].append(sys.argv[1:])
    save(state)
finally:
    release_lock(lock_fd)

args = sys.argv[1:]
if not args or args[0] not in ("release", "api"):
    sys.stderr.write("Unknown gh command\n")
    sys.exit(1)

if args[0] == "api":
    # gh api --method DELETE <asset api url>
    method = "GET"
    if "--method" in args:
        method = args[args.index("--method") + 1]
    url = args[-1]
    if method.upper() != "DELETE":
        sys.stderr.write("fake gh: only DELETE is supported\n")
        sys.exit(1)
    asset_id = url.rstrip("/").split("/")[-1]
    lock_fd = acquire_lock()
    try:
        state = load()
        removed = False
        for rel in state.get("releases", {}).values():
            assets = rel.get("assets", [])
            keep = [a for a in assets if str(a.get("id")) != asset_id]
            if len(keep) != len(assets):
                rel["assets"] = keep
                removed = True
        state["deleted_assets"] = state.get("deleted_assets", 0) + (1 if removed else 0)
        save(state)
    finally:
        release_lock(lock_fd)
    if not removed:
        sys.stderr.write("gh: Not Found (HTTP 404)\n")
        sys.exit(1)
    sys.exit(0)

sub = args[1]

if sub == "view":
    if state.get("fail_view_auth"):
        sys.stderr.write("gh: Bad credentials (HTTP 401)\n")
        sys.exit(1)
    if state.get("fail_view_network"):
        sys.stderr.write("gh: dial tcp: lookup api.github.com: i/o timeout\n")
        sys.exit(1)
    tag = args[2]
    rel = state["releases"].get(tag)
    if rel is None:
        sys.stderr.write(f"release not found: {tag}\n")
        sys.exit(1)
    if "--json" in args:
        assets = []
        for a in rel.get("assets", []):
            assets.append({
                "name": a["name"],
                "size": a.get("size", 0),
                "digest": a.get("digest"),
                "state": a.get("state", "uploaded"),
                "id": a.get("id", ""),
                "apiUrl": a.get("apiUrl", ""),
            })
        output = {
            "tagName": rel.get("tag", tag),
            "targetCommitish": rel.get("targetCommitish", tag),
            "url": rel.get("url", f"https://github.com/Flexy01/tvhome-launcher/releases/tag/{tag}"),
            "isDraft": rel.get("isDraft", False),
            "assets": assets
        }
        print(json.dumps(output))
        sys.exit(0)
    sys.exit(0)

if sub == "create":
    tag = args[2]
    asset_file = None
    for a in args[3:]:
        if not a.startswith("-") and os.path.isfile(a):
            asset_file = a
            break
    lock_fd = acquire_lock()
    try:
        state = load()
        if state.get("simulate_race_create"):
            # Simulate concurrent creation by another runner
            name = "tvhome-launcher.apk"
            if asset_file:
                with open(asset_file, "rb") as f:
                    content = f.read()
            else:
                content = b"PK\x03\x04" + b"A" * 1500000
            storage_dir = os.environ.get("FAKE_GH_STORAGE", "")
            if storage_dir:
                dest = os.path.join(storage_dir, f"{tag}_{name}")
                with open(dest, "wb") as f:
                    f.write(content)
            state["releases"][tag] = {
                "tag": tag,
                "isDraft": False,
                "assets": [make_asset(state, name, content)]
            }
            state["simulate_race_create"] = False
            save(state)
            sys.stderr.write(f"GraphQL: Validation Failed (release already exists: {tag})\n")
            sys.exit(1)
        if tag in state["releases"]:
            sys.stderr.write(f"release already exists: {tag}\n")
            sys.exit(1)
        if state.get("fail_create"):
            sys.stderr.write("gh: failed to create release\n")
            sys.exit(1)
        is_draft = "--draft" in args
        assets = []
        if asset_file and not state.get("simulate_empty_create"):
            name = os.path.basename(asset_file)
            with open(asset_file, "rb") as f:
                content = f.read()
            storage_dir = os.environ.get("FAKE_GH_STORAGE", "")
            if storage_dir:
                dest = os.path.join(storage_dir, f"{tag}_{name}")
                with open(dest, "wb") as f:
                    f.write(content)
            sha = hashlib.sha256(content).hexdigest()
            assets.append(make_asset(state, name, content))
        state["releases"][tag] = {
            "tag": tag,
            "isDraft": is_draft,
            "assets": assets
        }
        save(state)
    finally:
        release_lock(lock_fd)
    print(f"https://github.com/Flexy01/tvhome-launcher/releases/tag/{tag}")
    sys.exit(0)

if sub == "upload":
    tag = args[2]
    asset_file = None
    for a in args[3:]:
        if not a.startswith("-") and os.path.isfile(a):
            asset_file = a
            break
    lock_fd = acquire_lock()
    try:
        state = load()
        rel = state["releases"].get(tag)
        if rel is None:
            sys.stderr.write(f"release not found: {tag}\n")
            sys.exit(1)
        if state.get("fail_upload"):
            sys.stderr.write("gh: connection timeout during upload\n")
            sys.exit(1)
        if state.get("simulate_race_upload"):
            name = "tvhome-launcher.apk"
            if asset_file:
                with open(asset_file, "rb") as f:
                    content = f.read()
            else:
                content = b"PK\x03\x04" + b"A" * 1500000
            storage_dir = os.environ.get("FAKE_GH_STORAGE", "")
            if storage_dir:
                dest = os.path.join(storage_dir, f"{tag}_{name}")
                with open(dest, "wb") as f:
                    f.write(content)
            rel["assets"] = [make_asset(state, name, content)]
            state["simulate_race_upload"] = False
            save(state)
            sys.stderr.write(f"asset already exists: {name}\n")
            sys.exit(1)
        if state.get("simulate_race_upload_conflict"):
            # A concurrent runner uploaded a COMPLETE but different artifact.
            name = "tvhome-launcher.apk"
            content = b"PK\x03\x04" + b"CONCURRENT" * 150000
            storage_dir = os.environ.get("FAKE_GH_STORAGE", "")
            if storage_dir:
                dest = os.path.join(storage_dir, f"{tag}_{name}")
                with open(dest, "wb") as f:
                    f.write(content)
            rel["assets"] = [make_asset(state, name, content)]
            state["simulate_race_upload_conflict"] = False
            save(state)
            sys.stderr.write(f"asset already exists: {name}\n")
            sys.exit(1)
        asset_file = None
        for a in args[3:]:
            if not a.startswith("-") and os.path.isfile(a):
                asset_file = a
                break
        if not asset_file:
            sys.stderr.write("gh: missing asset file\n")
            sys.exit(1)
        name = os.path.basename(asset_file)
        existing = [a for a in rel.get("assets", []) if a["name"] == name]
        if existing and "--clobber" not in args:
            sys.stderr.write(f"asset already exists: {name}\n")
            sys.exit(1)
        with open(asset_file, "rb") as f:
            content = f.read()
        storage_dir = os.environ.get("FAKE_GH_STORAGE", "")
        if storage_dir:
            dest = os.path.join(storage_dir, f"{tag}_{name}")
            with open(dest, "wb") as f:
                f.write(content)
        sha = hashlib.sha256(content).hexdigest()
        assets = [a for a in rel.get("assets", []) if a["name"] != name]
        if not state.get("simulate_empty_upload"):
            assets.append(make_asset(state, name, content))
        rel["assets"] = assets
        save(state)
    finally:
        release_lock(lock_fd)
    sys.exit(0)

if sub == "edit":
    tag = args[2]
    lock_fd = acquire_lock()
    try:
        state = load()
        rel = state["releases"].get(tag)
        if rel is None:
            sys.stderr.write(f"release not found: {tag}\n")
            sys.exit(1)
        if "--draft=false" in args:
            rel["isDraft"] = False
        save(state)
    finally:
        release_lock(lock_fd)
    sys.exit(0)

if sub == "download":
    tag = args[2]
    dest_dir = "."
    pattern = None
    i = 3
    while i < len(args):
        if args[i] in ("-D", "--dir") and i + 1 < len(args):
            dest_dir = args[i + 1]
            i += 2
            continue
        if args[i] in ("-p", "--pattern") and i + 1 < len(args):
            pattern = args[i + 1]
            i += 2
            continue
        i += 1
    lock_fd = acquire_lock()
    try:
        state = load()
        rel = state["releases"].get(tag)
        if rel is None:
            sys.stderr.write(f"release not found: {tag}\n")
            sys.exit(1)
        if state.get("simulate_asset_vanishes"):
            # The asset is deleted between the state query and the download.
            rel["assets"] = []
            state["simulate_asset_vanishes"] = False
            save(state)
            sys.stderr.write("no assets found matching pattern\n")
            sys.exit(1)
        matched = [a for a in rel.get("assets", []) if not pattern or pattern == a["name"]]
        if not matched:
            sys.stderr.write("no assets found matching pattern\n")
            sys.exit(1)
        state["downloads"] = state.get("downloads", 0) + 1
        truncate = state.get("truncate_downloads", 0) > 0
        if truncate:
            state["truncate_downloads"] -= 1
        corrupt = bool(state.get("simulate_corrupt_download"))
        save(state)
        payloads = []
        for m in matched:
            storage_dir = os.environ.get("FAKE_GH_STORAGE", "")
            src_stored = os.path.join(storage_dir, f"{tag}_{m['name']}") if storage_dir else None
            if src_stored and os.path.exists(src_stored):
                with open(src_stored, "rb") as f:
                    content = f.read()
            else:
                content = b"PK\x03\x04" + b"X" * max(0, m.get("size", 0) - 4)
            if truncate:
                # An interrupted download: fewer bytes than GitHub published.
                content = content[: len(content) // 2]
            elif corrupt:
                # A corrupted download: the same size, different bytes.
                content = b"\x00" * len(content)
            payloads.append((m["name"], content))
    finally:
        release_lock(lock_fd)
    os.makedirs(dest_dir, exist_ok=True)
    for name, content in payloads:
        with open(os.path.join(dest_dir, name), "wb") as f:
            f.write(content)
    sys.exit(0)

sys.stderr.write(f"Unknown sub-command: {sub}\n")
sys.exit(1)
'''

FAKE_APKSIGNER_SCRIPT = r'''
import hashlib
import os
import sys
import zipfile

args = sys.argv[1:]
if not args or args[0] != "verify":
    sys.stderr.write("fake apksigner: only verify supported\n")
    sys.exit(1)

print_certs = "--print-certs" in args
apk_file = args[-1]

if not os.path.isfile(apk_file):
    sys.stderr.write(f"DOES NOT EXIST: {apk_file}\n")
    sys.exit(1)

try:
    with zipfile.ZipFile(apk_file, "r") as z:
        cert_names = [n for n in z.namelist() if n.startswith("META-INF/") and n.endswith(".RSA")]
        if not cert_names:
            sys.stderr.write("DOES NOT VERIFY: unsigned APK (missing META-INF/*.RSA)\n")
            sys.exit(1)
        cert_data = z.read(cert_names[0])
except Exception as e:
    sys.stderr.write(f"DOES NOT VERIFY: {e}\n")
    sys.exit(1)

cert_sha = hashlib.sha256(cert_data).hexdigest()

if print_certs:
    print(f"Signer #1 certificate SHA-256 digest: {cert_sha}")
    sys.exit(0)

# verify mode
sys.exit(0)
'''


def make_valid_test_apk(extra_payload=b"", package_name="com.gathin.tvhome", cert_content=b"PROD_RELEASE_KEY_CERT_2026"):
    import io, zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("AndroidManifest.xml", f"package={package_name}".encode())
        z.writestr("classes.dex", b"DEX" + extra_payload)
        z.writestr("META-INF/CERT.RSA", cert_content)
        z.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
    data = buf.getvalue()
    padding_needed = max(0, 1500000 - len(data))
    if padding_needed > 0:
        buf2 = io.BytesIO()
        with zipfile.ZipFile(buf2, "w") as z:
            z.writestr("AndroidManifest.xml", f"package={package_name}".encode())
            z.writestr("classes.dex", b"DEX" + extra_payload)
            z.writestr("assets/padding.dat", b"P" * padding_needed)
            z.writestr("META-INF/CERT.RSA", cert_content)
            z.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        data = buf2.getvalue()
    return data


class ReleaseAutomationTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)

        self.bin_dir = self.base / "bin"
        self.bin_dir.mkdir(parents=True)

        self.storage_dir = self.base / "storage"
        self.storage_dir.mkdir(parents=True)

        self.state_file = self.base / "gh-state.json"
        self.script_path = ROOT / "tools" / "publish_release.sh"

        fake_py = self.base / "fake_gh.py"
        fake_py.write_text(FAKE_GH_SCRIPT, encoding="utf-8")

        gh_wrapper = self.bin_dir / "gh"
        gh_wrapper.write_text(
            f'#!/bin/sh\nexec "{sys.executable}" "{fake_py}" "$@"\n',
            encoding="utf-8"
        )
        gh_wrapper.chmod(0o755)

        fake_apksigner_py = self.base / "fake_apksigner.py"
        fake_apksigner_py.write_text(FAKE_APKSIGNER_SCRIPT, encoding="utf-8")
        apksigner_wrapper = self.bin_dir / "apksigner"
        apksigner_wrapper.write_text(
            f'#!/bin/sh\nexec "{sys.executable}" "{fake_apksigner_py}" "$@"\n',
            encoding="utf-8"
        )
        apksigner_wrapper.chmod(0o755)

        self.valid_apk = self.base / "tvhome-launcher.apk"
        self.valid_apk_content = make_valid_test_apk()
        self.valid_apk.write_bytes(self.valid_apk_content)
        self.valid_apk_sha = hashlib.sha256(self.valid_apk_content).hexdigest()

    def _init_state(self, releases=None, fail_create=False, fail_upload=False,
                    fail_view_auth=False, fail_view_network=False,
                    simulate_empty_create=False, simulate_empty_upload=False,
                    simulate_corrupt_download=False,
                    simulate_race_create=False, simulate_race_upload=False,
                    simulate_race_upload_conflict=False,
                    simulate_asset_vanishes=False, truncate_downloads=0):
        state = {
            "calls": [],
            "releases": releases or {},
            "fail_create": fail_create,
            "fail_upload": fail_upload,
            "fail_view_auth": fail_view_auth,
            "fail_view_network": fail_view_network,
            "simulate_empty_create": simulate_empty_create,
            "simulate_empty_upload": simulate_empty_upload,
            "simulate_corrupt_download": simulate_corrupt_download,
            "simulate_race_create": simulate_race_create,
            "simulate_race_upload": simulate_race_upload,
            "simulate_race_upload_conflict": simulate_race_upload_conflict,
            "simulate_asset_vanishes": simulate_asset_vanishes,
            "truncate_downloads": truncate_downloads,
        }
        self.state_file.write_text(json.dumps(state), encoding="utf-8")

    def _run_script(self, tag, apk_path):
        env = dict(os.environ)
        env["PATH"] = f"{self.bin_dir}{os.pathsep}{env.get('PATH', '')}"
        env["FAKE_GH_STATE"] = str(self.state_file)
        env["FAKE_GH_STORAGE"] = str(self.storage_dir)
        git_bash = Path(r"C:\Program Files\Git\bin\bash.exe")
        if git_bash.exists():
            bash = str(git_bash)
        else:
            bash = shutil.which("bash") or "bash"
        return subprocess.run(
            [bash, str(self.script_path), tag, str(apk_path)],
            capture_output=True,
            text=True,
            env=env
        )

    def test_release_is_created_as_draft_and_published_after_verification(self):
        # The release only becomes public after the artifact is uploaded and
        # verified: an interrupted publication must not leave a public release
        # without a valid artifact.
        self._init_state(releases={})
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        create_calls = [c for c in state["calls"] if c[0] == "release" and c[1] == "create"]
        self.assertTrue(create_calls, "the release must be created")
        self.assertIn("--draft", create_calls[0], "the release must be created as a draft")
        self.assertFalse(state["releases"]["v1.2"]["isDraft"],
                         "the verified release must be published")
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertIn("release edit", call_commands)

    def test_first_release_creation(self):
        self._init_state(releases={})
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")
        self.assertIn("SUCCESS: Verified published release", res.stdout)
        self.assertIn(self.valid_apk_sha, res.stdout)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertIn("v1.2", state["releases"])
        rel = state["releases"]["v1.2"]
        self.assertFalse(rel.get("isDraft", False))
        assets = rel["assets"]
        self.assertEqual(len(assets), 1)
        self.assertEqual(assets[0]["name"], "tvhome-launcher.apk")
        self.assertEqual(assets[0]["sha256"], self.valid_apk_sha)

        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertIn("release create", call_commands)
        self.assertNotIn("release upload", call_commands)

    def test_existing_release_with_matching_sha256_is_idempotent(self):
        dest = self.storage_dir / "v1.2_tvhome-launcher.apk"
        dest.write_bytes(self.valid_apk_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": False,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content), "sha256": self.valid_apk_sha}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")
        self.assertIn("Preserving verified asset", res.stdout)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release create", call_commands)
        self.assertNotIn("release upload", call_commands)

    def test_existing_published_release_with_different_sha256_refuses_overwrite(self):
        other_content = b"PK\x03\x04" + b"Z" * 1400000
        other_sha = hashlib.sha256(other_content).hexdigest()
        dest = self.storage_dir / "v1.2_tvhome-launcher.apk"
        dest.write_bytes(other_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": False,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(other_content), "sha256": other_sha}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Script must fail on conflicting release asset")
        self.assertIn("Existing valid releases and assets must not be destroyed", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release upload", call_commands)
        self.assertNotIn("release create", call_commands)

    def test_interrupted_draft_release_recovery(self):
        # Interrupted publication left a draft release without assets
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": []
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Recovery script failed: {res.stderr}\n{res.stdout}")
        self.assertIn("SUCCESS: Verified published release", res.stdout)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertFalse(rel["isDraft"], "Draft release must be published after recovery")
        self.assertEqual(len(rel["assets"]), 1)

        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertIn("release upload", call_commands)
        self.assertIn("release edit", call_commands)
        self.assertNotIn("release create", call_commands)

    def test_auth_failure_does_not_create_or_confuse_with_404(self):
        self._init_state(fail_view_auth=True)
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("Authentication or permission failure", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release create", call_commands)

    def test_network_failure_does_not_create_or_confuse_with_404(self):
        self._init_state(fail_view_network=True)
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("Network or API failure", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release create", call_commands)

    def test_post_verification_fails_if_downloaded_sha256_mismatches(self):
        self._init_state(releases={}, simulate_corrupt_download=True)
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("SHA-256 mismatch", res.stderr)

    def test_post_verification_fails_if_release_is_draft(self):
        dest = self.storage_dir / "v1.2_tvhome-launcher.apk"
        dest.write_bytes(self.valid_apk_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content), "sha256": self.valid_apk_sha}]
            }
        })
        # If script fails to publish the draft, post-verification must reject it
        res = self._run_script("v1.2", self.valid_apk)
        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        # If release is still draft, returncode must be non-zero
        if state["releases"]["v1.2"]["isDraft"]:
            self.assertNotEqual(res.returncode, 0)
            self.assertIn("draft state", res.stderr)

    def test_post_verification_fails_if_multiple_apk_assets(self):
        dest1 = self.storage_dir / "v1.2_tvhome-launcher.apk"
        dest1.write_bytes(self.valid_apk_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": False,
                "assets": [
                    {"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content), "sha256": self.valid_apk_sha},
                    {"name": "extra-launcher.apk", "size": 1000, "sha256": "fake"}
                ]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("Expected exactly 1 APK asset", res.stderr)

    def test_upload_failure_does_not_fallback_to_create(self):
        self._init_state(releases={"v1.2": {"tag": "v1.2", "isDraft": False, "assets": []}}, fail_upload=True)
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Script should have failed on upload failure")

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertIn("release upload", call_commands)
        self.assertNotIn("release create", call_commands)

    def test_missing_artifact_fails_fast(self):
        self._init_state(releases={})
        missing = self.base / "nonexistent.apk"
        res = self._run_script("v1.2", missing)
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("does not exist", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertEqual(len(state["calls"]), 0)

    def test_empty_artifact_fails_fast(self):
        self._init_state(releases={})
        empty_apk = self.base / "empty.apk"
        empty_apk.write_bytes(b"")
        res = self._run_script("v1.2", empty_apk)
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("empty", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertEqual(len(state["calls"]), 0)

    def test_oversized_artifact_fails_fast(self):
        self._init_state(releases={})
        large_apk = self.base / "large.apk"
        large_apk.write_bytes(b"A" * 2100000)
        res = self._run_script("v1.2", large_apk)
        self.assertNotEqual(res.returncode, 0)
        self.assertIn("exceeds 2 MiB budget", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertEqual(len(state["calls"]), 0)

    def test_concurrent_create_race_recovers_and_converges(self):
        # Runner A sees release does not exist, but by the time create is called,
        # a concurrent runner created it. Runner A must reconcile and succeed.
        self._init_state(releases={}, simulate_race_create=True)
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")
        self.assertIn("SUCCESS: Verified published release", res.stdout)
        self.assertIn(self.valid_apk_sha, res.stdout)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertIn("v1.2", state["releases"])
        self.assertFalse(state["releases"]["v1.2"]["isDraft"])
        self.assertEqual(len(state["releases"]["v1.2"]["assets"]), 1)

    def test_concurrent_upload_race_recovers_and_converges(self):
        # Runner A sees release exists without assets. Before Runner A's upload executes,
        # a concurrent runner uploaded the verified asset. Runner A must reconcile and succeed.
        self._init_state(
            releases={"v1.2": {"tag": "v1.2", "isDraft": False, "assets": []}},
            simulate_race_upload=True
        )
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")
        self.assertIn("SUCCESS: Verified published release", res.stdout)
        self.assertIn(self.valid_apk_sha, res.stdout)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertIn("v1.2", state["releases"])
        self.assertFalse(state["releases"]["v1.2"]["isDraft"])
        self.assertEqual(len(state["releases"]["v1.2"]["assets"]), 1)

    def test_simultaneous_parallel_executions_both_succeed(self):
        # Two processes run publish_release.sh concurrently on the same tag with valid APK.
        self._init_state(releases={})
        import concurrent.futures

        def run_one():
            return self._run_script("v1.2", self.valid_apk)

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            f1 = executor.submit(run_one)
            f2 = executor.submit(run_one)
            res1 = f1.result()
            res2 = f2.result()

        self.assertEqual(res1.returncode, 0, f"Runner 1 failed: {res1.stderr}\n{res1.stdout}")
        self.assertEqual(res2.returncode, 0, f"Runner 2 failed: {res2.stderr}\n{res2.stdout}")

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertIn("v1.2", state["releases"])
        rel = state["releases"]["v1.2"]
        self.assertFalse(rel.get("isDraft", False))
        self.assertEqual(len(rel.get("assets", [])), 1)
        self.assertEqual(rel["assets"][0]["sha256"], self.valid_apk_sha)

    def test_concurrent_execution_with_conflicting_asset_rejects_second_runner(self):
        # Runner 1 publishes valid APK; Runner 2 tries to publish conflicting APK to same release.
        dest = self.storage_dir / "v1.2_tvhome-launcher.apk"
        dest.write_bytes(self.valid_apk_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": False,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content), "sha256": self.valid_apk_sha}]
            }
        })

        other_dir = self.base / "other_runner"
        other_dir.mkdir()
        other_apk = other_dir / "tvhome-launcher.apk"
        other_content = b"PK\x03\x04" + b"CONFLICTING" * 100000
        other_apk.write_bytes(other_content)

        res = self._run_script("v1.2", other_apk)
        self.assertNotEqual(res.returncode, 0, "Conflicting asset must be rejected")
        self.assertIn("Existing valid releases and assets must not be destroyed", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertEqual(len(rel["assets"]), 1)
        self.assertEqual(rel["assets"][0]["sha256"], self.valid_apk_sha)

    def test_release_workflow_preserves_pending_publication_requests(self):
        # GitHub keeps one pending run per concurrency group by default and
        # replaces it with the next one; `queue: max` raises that to GitHub's
        # supported limit of 100 pending runs. The group stays shared per tag so
        # only one run publishes a tag at a time.
        workflow = (ROOT / ".github" / "workflows" / "release.yml").read_text(encoding="utf-8")
        match = re.search(r"^concurrency:\n(?:[ \t]+.*\n)+", workflow, re.M)
        self.assertIsNotNone(match, "release workflow must declare a concurrency block")
        block = match.group(0) if match else ""
        settings = "\n".join(line for line in block.splitlines()
                             if line.strip() and not line.strip().startswith("#"))
        self.assertIn("queue: max", settings,
                      "pending publication requests must be queued, not silently replaced")
        self.assertIn("cancel-in-progress: false", settings,
                      "a running publication must never be cancelled mid-flight")
        self.assertNotIn("cancel-in-progress: true", settings,
                         "queue: max and cancel-in-progress: true are rejected by GitHub")
        self.assertIn("github.ref_name", block,
                      "the group must be shared by every run of the same tag")
        self.assertNotIn("github.run_id", block,
                         "same-tag runs must not execute in parallel")

    def test_draft_release_with_conflicting_asset_is_not_overwritten(self):
        # A draft release holding an unverified or conflicting artifact (e.g. wrong package identity)
        # must be rejected and must NEVER be silently overwritten or published.
        import io, zipfile
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("AndroidManifest.xml", b"package=com.conflict.rogueapp")
            z.writestr("classes.dex", b"CONFLICT" * 20000)
        other_content = buf.getvalue()
        other_sha = hashlib.sha256(other_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(other_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(other_content), "sha256": other_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "A conflicting draft artifact must not be silently overwritten or published")
        self.assertIn("failed provenance verification", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "The pending publication request must stay draft")
        self.assertEqual(len(rel["assets"]), 1)
        self.assertEqual(rel["assets"][0]["sha256"], other_sha, "Conflicting artifact must be preserved")
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release upload", call_commands)

    def test_concurrent_draft_upload_with_conflicting_sha256_refuses_overwrite(self):
        # A concurrent runner uploads a complete but different artifact while this
        # runner is uploading. The collision must not end in a silent overwrite.
        self._init_state(
            releases={"v1.2": {"tag": "v1.2", "isDraft": True, "assets": []}},
            simulate_race_upload_conflict=True
        )
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Conflicting concurrent upload must be rejected")
        self.assertIn("Existing valid releases and assets must not be destroyed", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "The draft must not be published over a conflicting artifact")
        self.assertEqual(len(rel["assets"]), 1)
        self.assertNotEqual(rel["assets"][0]["sha256"], self.valid_apk_sha)

    def test_interrupted_draft_with_incomplete_asset_is_repaired_and_published(self):
        # GitHub reports an upload that never finished as a starter asset:
        # state "open", no digest, zero bytes. It never carried a published
        # artifact, so it is the one case where an existing asset may be replaced.
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": 0, "sha256": None,
                            "digest": None, "state": "open"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertFalse(rel["isDraft"], "Recovered draft must be published")
        self.assertEqual(len(rel["assets"]), 1)
        self.assertEqual(rel["assets"][0]["sha256"], self.valid_apk_sha)
        self.assertEqual(rel["assets"][0]["state"], "uploaded")
        self.assertEqual(state.get("deleted_assets"), 1,
                         "the incomplete asset must be deleted by its own id")
        self.assertFalse(any("--clobber" in c for c in state["calls"]),
                         "replacement must not clobber by name: a concurrent upload could be destroyed")
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release create", call_commands)

    def test_published_release_with_incomplete_asset_is_repaired(self):
        # A published release whose upload never finished still has to converge
        # on one verified artifact: the starter asset holds no published bytes.
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": False,
                "assets": [{"name": "tvhome-launcher.apk", "size": 0, "sha256": None,
                            "digest": None, "state": "open"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertEqual(len(rel["assets"]), 1)
        self.assertEqual(rel["assets"][0]["sha256"], self.valid_apk_sha)

    def test_interrupted_download_is_retried_without_touching_the_release(self):
        # The published asset is the verified one, but our download is cut short.
        # The truncation is detected against GitHub's own size and retried; the
        # release must not be modified.
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(self.valid_apk_content)
        self._init_state(
            releases={"v1.2": {"tag": "v1.2", "isDraft": False, "assets": [
                {"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content),
                 "sha256": self.valid_apk_sha}]}},
            truncate_downloads=1
        )
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertGreaterEqual(state.get("downloads", 0), 2,
                                "the interrupted download must be retried")
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release upload", call_commands)
        self.assertEqual(state["releases"]["v1.2"]["assets"][0]["sha256"], self.valid_apk_sha)

    def test_persistent_interrupted_download_fails_without_modifying_the_release(self):
        # If the copy cannot be verified against GitHub's metadata, the release
        # must be left exactly as it is.
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(self.valid_apk_content)
        self._init_state(
            releases={"v1.2": {"tag": "v1.2", "isDraft": False, "assets": [
                {"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content),
                 "sha256": self.valid_apk_sha}]}},
            truncate_downloads=5
        )
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "an unverifiable download must fail the publication")
        self.assertIn("interrupted", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release upload", call_commands)
        self.assertNotIn("release create", call_commands)
        self.assertEqual(state["releases"]["v1.2"]["assets"][0]["sha256"], self.valid_apk_sha)

    def test_asset_without_digest_is_verified_by_download(self):
        # Releases uploaded before GitHub published asset digests carry no
        # digest: the bytes have to be verified by downloading them.
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(self.valid_apk_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": False,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content),
                            "sha256": self.valid_apk_sha, "digest": None, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")
        self.assertIn("Preserving verified asset", res.stdout)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release upload", call_commands)

    def test_asset_without_digest_and_different_bytes_is_refused(self):
        other_content = b"PK\x03\x04" + b"Z" * 1400000
        other_sha = hashlib.sha256(other_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(other_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": False,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(other_content),
                            "sha256": other_sha, "digest": None, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "a conflicting artifact must be refused")
        self.assertIn("Existing valid releases and assets must not be destroyed", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release upload", call_commands)
        self.assertEqual(state["releases"]["v1.2"]["assets"][0]["sha256"], other_sha)

    def test_conflicting_digest_on_published_release_is_refused_without_downloading(self):
        # On an already published release, GitHub's own digest decides the conflict:
        # no download is needed and no byte of the other build is read.
        other_content = b"PK\x03\x04" + b"CONFLICT" * 200000
        other_sha = hashlib.sha256(other_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(other_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": False,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(other_content),
                            "sha256": other_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "A conflicting published artifact must not be silently overwritten")
        self.assertIn("Existing valid releases and assets must not be destroyed", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertEqual(state.get("downloads", 0), 0,
                         "the authoritative digest must decide without downloading on published releases")
        rel = state["releases"]["v1.2"]
        self.assertFalse(rel["isDraft"], "The published release must remain published")
        self.assertEqual(len(rel["assets"]), 1)
        self.assertEqual(rel["assets"][0]["sha256"], other_sha, "Conflicting artifact must be preserved")

    def test_draft_release_with_matching_asset_is_published_without_reupload(self):
        # Publication interrupted after the upload but before the release went
        # public: the verified artifact is already there, only publication is left.
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(self.valid_apk_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content), "sha256": self.valid_apk_sha}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")
        self.assertIn("Preserving verified asset", res.stdout)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        self.assertFalse(state["releases"]["v1.2"]["isDraft"])
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release upload", call_commands)

    def test_asset_deleted_between_state_query_and_download_is_uploaded_again(self):
        # The asset can disappear between reading the release state and
        # downloading it. The publication must still converge on one artifact.
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(self.valid_apk_content)
        self._init_state(
            releases={"v1.2": {"tag": "v1.2", "isDraft": False, "assets": [
                {"name": "tvhome-launcher.apk", "size": len(self.valid_apk_content), "sha256": self.valid_apk_sha}]}},
            simulate_asset_vanishes=True
        )
        res = self._run_script("v1.2", self.valid_apk)
        self.assertEqual(res.returncode, 0, f"Script failed: {res.stderr}\n{res.stdout}")

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertFalse(rel["isDraft"])
        self.assertEqual(len(rel["assets"]), 1)
        self.assertEqual(rel["assets"][0]["sha256"], self.valid_apk_sha)


    def test_interrupted_draft_retry_with_different_rebuilt_apk_recovers_canonical_artifact(self):
        # Interrupted publication: attempt 1 uploaded a valid canonical artifact (canonical_sha)
        # to draft release v1.2, but the run died before publication (--draft=false).
        # A retry run rebuilds the APK. Because Android builds are non-deterministic,
        # the rebuilt APK has a different SHA-256 (rebuilt_sha != canonical_sha).
        # The retry must NOT be blocked! It must establish the uploaded artifact as canonical,
        # verify its integrity and provenance, and publish the release with that exact artifact.
        canonical_content = make_valid_test_apk(extra_payload=b"CANONICAL_BUILD_1")
        canonical_sha = hashlib.sha256(canonical_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(canonical_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(canonical_content),
                            "sha256": canonical_sha, "state": "uploaded"}]
            }
        })

        rebuilt_dir = self.base / "rebuilt"
        rebuilt_dir.mkdir()
        rebuilt_apk = rebuilt_dir / "tvhome-launcher.apk"
        rebuilt_content = make_valid_test_apk(extra_payload=b"REBUILT_ATTEMPT_2")
        rebuilt_apk.write_bytes(rebuilt_content)
        rebuilt_sha = hashlib.sha256(rebuilt_content).hexdigest()
        self.assertNotEqual(canonical_sha, rebuilt_sha, "Rebuilt APK must have distinct SHA-256")

        res = self._run_script("v1.2", rebuilt_apk)
        self.assertEqual(res.returncode, 0, f"Recovery script failed: {res.stderr}\n{res.stdout}")
        self.assertIn("canonical artifact", res.stdout)
        self.assertIn(canonical_sha, res.stdout)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertFalse(rel["isDraft"], "Draft release must be published after canonical recovery")
        self.assertEqual(len(rel["assets"]), 1, "Exactly one asset must be on release")
        self.assertEqual(rel["assets"][0]["sha256"], canonical_sha,
                         "Canonical artifact must be preserved, NOT overwritten by rebuilt APK")
        self.assertEqual(state.get("deleted_assets", 0), 0, "Canonical asset must never be deleted")
        call_commands = [c[0] + " " + c[1] for c in state["calls"]]
        self.assertNotIn("release upload", call_commands, "Canonical asset must not be re-uploaded")
        self.assertIn("release edit", call_commands, "Release must be published via release edit")


    def test_draft_release_with_oversized_unverified_artifact_is_rejected(self):
        # A draft release holding an artifact that exceeds the 2 MiB budget is an
        # unverified or conflicting artifact: it must be rejected without publishing.
        oversized_content = b"PK\x03\x04" + b"BIG" * 800000 # 2,400,004 bytes > 2 MiB
        oversized_sha = hashlib.sha256(oversized_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(oversized_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(oversized_content),
                            "sha256": oversized_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Oversized draft artifact must be rejected")
        self.assertIn("exceeds 2 MiB budget", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")
        self.assertEqual(rel["assets"][0]["sha256"], oversized_sha, "Remote asset must not be modified")

    def test_draft_release_with_non_apk_magic_is_rejected(self):
        # A draft artifact that does not start with PK\x03\x04 is not a valid APK:
        # it must be rejected as an unverified build without publishing.
        non_apk_content = b"NOT_A_VALID_ZIP_ARCHIVE_AT_ALL_" * 10000
        non_apk_sha = hashlib.sha256(non_apk_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(non_apk_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(non_apk_content),
                            "sha256": non_apk_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Non-APK draft artifact must be rejected")
        self.assertIn("not a valid APK/ZIP archive", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")

    def test_draft_release_with_conflicting_package_identity_is_rejected(self):
        # A draft artifact whose AndroidManifest.xml package identity does not match
        # com.gathin.tvhome is a conflicting build: it must be rejected.
        import io, zipfile
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("AndroidManifest.xml", b"package=com.rogue.malicious")
            z.writestr("classes.dex", b"DEX")
        rogue_content = buf.getvalue()
        rogue_sha = hashlib.sha256(rogue_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(rogue_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(rogue_content),
                            "sha256": rogue_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Conflicting package identity must be rejected")
        self.assertIn("package identity does not match", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")

    def test_draft_release_with_corrupted_zip_archive_is_rejected(self):
        # A draft artifact that starts with PK\x03\x04 but is truncated or has corrupted
        # central directory/CRC is a malformed archive: it must fail closed and be rejected.
        corrupted_content = b"PK\x03\x04" + b"CORRUPTED_ZIP_DATA" * 50000
        corrupted_sha = hashlib.sha256(corrupted_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(corrupted_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(corrupted_content),
                            "sha256": corrupted_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Corrupted zip draft artifact must be rejected")
        self.assertIn("failed provenance verification", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")

    def test_draft_release_missing_android_manifest_is_rejected(self):
        # A draft artifact that is a valid zip but does not contain AndroidManifest.xml
        # is missing mandatory package identity evidence: it must fail closed and be rejected.
        import io, zipfile
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("classes.dex", b"DEX_WITHOUT_MANIFEST" * 50000)
            z.writestr("META-INF/CERT.RSA", b"CERT")
        no_manifest_content = buf.getvalue()
        no_manifest_sha = hashlib.sha256(no_manifest_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(no_manifest_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(no_manifest_content),
                            "sha256": no_manifest_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Artifact missing AndroidManifest.xml must be rejected")
        self.assertIn("failed provenance verification", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")

    def test_draft_release_with_missing_apksigner_tool_fails_closed(self):
        # If apksigner verification tool is missing, the system must NOT fail open.
        # It must safely reject the unverified draft artifact without publishing.
        canonical_content = make_valid_test_apk(extra_payload=b"CANONICAL_BUILD")
        canonical_sha = hashlib.sha256(canonical_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(canonical_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(canonical_content),
                            "sha256": canonical_sha, "state": "uploaded"}]
            }
        })
        # Remove apksigner from bin_dir
        apksigner_path = self.bin_dir / "apksigner"
        if apksigner_path.exists():
            apksigner_path.unlink()

        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Missing apksigner tool must cause fail-closed rejection")
        self.assertIn("failed provenance verification", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")

    def test_draft_release_with_unsigned_artifact_is_rejected(self):
        # A draft artifact without a valid production signature (unsigned APK)
        # must fail verification and be rejected.
        import io, zipfile
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w") as z:
            z.writestr("AndroidManifest.xml", b"package=com.gathin.tvhome")
            z.writestr("classes.dex", b"UNSIGNED_DEX" * 50000)
        unsigned_content = buf.getvalue()
        unsigned_sha = hashlib.sha256(unsigned_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(unsigned_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(unsigned_content),
                            "sha256": unsigned_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Unsigned draft artifact must be rejected")
        self.assertIn("failed provenance verification", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")

    def test_draft_release_with_signer_certificate_mismatch_is_rejected(self):
        # A draft artifact signed with a different certificate than the local build
        # is a conflicting/unauthorized build: it must be rejected.
        other_cert_content = make_valid_test_apk(extra_payload=b"OTHER_CERT", cert_content=b"ROGUE_ATTACKER_CERT")
        other_cert_sha = hashlib.sha256(other_cert_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(other_cert_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(other_cert_content),
                            "sha256": other_cert_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Signer certificate mismatch must be rejected")
        self.assertIn("failed provenance verification", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")

    def test_draft_release_from_conflicting_commit_is_rejected(self):
        # A draft release whose targetCommitish does not match the tag commit
        # indicates an artifact built from an untrusted or conflicting commit.
        # It must be rejected without publishing.
        canonical_content = make_valid_test_apk(extra_payload=b"CANONICAL_BUILD")
        canonical_sha = hashlib.sha256(canonical_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(canonical_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "targetCommitish": "conflicting_unrelated_commit_sha_12345",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(canonical_content),
                            "sha256": canonical_sha, "state": "uploaded"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Conflicting commit on draft release must be rejected")
        self.assertIn("target commitish", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")

    def test_draft_release_from_conflicting_repository_is_rejected(self):
        # A draft release whose URL does not match the trusted repository
        # must be rejected without publishing.
        canonical_content = make_valid_test_apk(extra_payload=b"CANONICAL_BUILD")
        canonical_sha = hashlib.sha256(canonical_content).hexdigest()
        (self.storage_dir / "v1.2_tvhome-launcher.apk").write_bytes(canonical_content)
        self._init_state(releases={
            "v1.2": {
                "tag": "v1.2",
                "url": "https://github.com/RogueAttacker/tvhome-launcher/releases/tag/v1.2",
                "isDraft": True,
                "assets": [{"name": "tvhome-launcher.apk", "size": len(canonical_content),
                            "sha256": canonical_sha, "state": "uploaded",
                            "apiUrl": "https://api.github.com/repos/RogueAttacker/tvhome-launcher/releases/assets/999"}]
            }
        })
        res = self._run_script("v1.2", self.valid_apk)
        self.assertNotEqual(res.returncode, 0, "Conflicting repository must be rejected")
        self.assertIn("repository mismatch", res.stderr)

        state = json.loads(self.state_file.read_text(encoding="utf-8"))
        rel = state["releases"]["v1.2"]
        self.assertTrue(rel["isDraft"], "Unverified release must remain draft")


if __name__ == "__main__":
    unittest.main()
