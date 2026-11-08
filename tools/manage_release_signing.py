#!/usr/bin/env python3
"""
TvHome Launcher - Secure Release Signing Identity & Keystore Management

Manages creation, secure backup, verification, restoration, and CI secret
export for the official TvHome Launcher production APK signing identity.

Keystore parameters adhere to modern Android standards:
- Type: PKCS12
- Algorithm: RSA 4096-bit
- Signature: SHA256withRSA
- Validity: 10000 days (~27 years)
- Alias: tvhome-release
"""

import os
import sys
import json
import base64
import shutil
import hashlib
import zipfile
import secrets
import tempfile
import argparse
import subprocess
from pathlib import Path


from typing import Optional


DEFAULT_SIGNING_DIR = os.path.expanduser("~/tvhome-launcher-signing")
DEFAULT_KEYSTORE_NAME = "tvhome-release.keystore"
DEFAULT_PROPERTIES_NAME = "keystore.properties"
DEFAULT_ALIAS = "tvhome-release"
DEFAULT_DNAME = "CN=TvHome Release, OU=TvHome Launcher, O=Flexy01, L=Istanbul, C=TR"
DEFAULT_KEY_SIZE = 4096
DEFAULT_VALIDITY_DAYS = 10000


def find_keytool() -> str:
    """Locate keytool executable from PATH, JAVA_HOME, or common JDK paths."""
    # 1. PATH lookup
    bin_name = "keytool.exe" if sys.platform == "win32" else "keytool"
    found = shutil.which(bin_name) or shutil.which("keytool")
    if found and os.path.isfile(found):
        return os.path.abspath(found)

    # 2. JAVA_HOME lookup
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = os.path.join(java_home, "bin", bin_name)
        if os.path.isfile(candidate):
            return os.path.abspath(candidate)

    # 3. Common Windows JDK installations
    if sys.platform == "win32":
        search_roots = [
            r"C:\Program Files\Microsoft",
            r"C:\Program Files\Java",
            r"C:\Program Files\Eclipse Adoptium",
            r"C:\Program Files\Android\Android Studio\jbr",
        ]
        for root in search_roots:
            if os.path.isdir(root):
                for path in Path(root).rglob(bin_name):
                    if path.is_file():
                        return str(path.resolve())

    raise FileNotFoundError("keytool executable not found in PATH, JAVA_HOME, or standard JDK directories.")


def find_apksigner() -> str:
    """Locate apksigner executable from PATH or Android SDK build-tools."""
    bin_name = "apksigner.bat" if sys.platform == "win32" else "apksigner"
    found = shutil.which(bin_name) or shutil.which("apksigner")
    if found and os.path.isfile(found):
        return os.path.abspath(found)

    android_home = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not android_home and sys.platform == "win32":
        local_appdata = os.environ.get("LOCALAPPDATA", "")
        candidate_sdk = os.path.join(local_appdata, "Android", "Sdk")
        if os.path.isdir(candidate_sdk):
            android_home = candidate_sdk

    if android_home:
        build_tools_dir = os.path.join(android_home, "build-tools")
        if os.path.isdir(build_tools_dir):
            candidates = sorted(Path(build_tools_dir).rglob(bin_name), reverse=True)
            for c in candidates:
                if c.is_file():
                    return str(c.resolve())

    raise FileNotFoundError("apksigner executable not found in PATH or Android SDK build-tools.")


def sha256_file(filepath: str) -> str:
    """Calculate SHA-256 digest of a file."""
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest().lower()


def read_properties(filepath: str) -> dict:
    """Read a Java-style .properties file into a Python dict."""
    props = {}
    if not os.path.isfile(filepath):
        return props
    with open(filepath, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or line.startswith("!"):
                continue
            if "=" in line:
                k, v = line.split("=", 1)
                k = k.strip().lstrip("\ufeff")  # Strip UTF-8 BOM if present
                v = v.strip()
                props[k] = v
    return props


def write_properties(filepath: str, props: dict) -> None:
    """Write dictionary to a Java-style .properties file."""
    with open(filepath, "w", encoding="utf-8") as f:
        f.write("# TvHome Launcher Production Signing Configuration\n")
        f.write("# KEEP SECURE. NEVER COMMIT OR PUSH TO REPOSITORY.\n")
        for k, v in props.items():
            f.write(f"{k}={v}\n")


def generate_keystore(
    target_dir: str = DEFAULT_SIGNING_DIR,
    alias: str = DEFAULT_ALIAS,
    dname: str = DEFAULT_DNAME,
    key_size: int = DEFAULT_KEY_SIZE,
    validity_days: int = DEFAULT_VALIDITY_DAYS,
    password: Optional[str] = None,
    force: bool = False,
) -> dict:
    """Generate production PKCS12 keystore and keystore.properties."""
    os.makedirs(target_dir, exist_ok=True)
    keystore_path = os.path.join(target_dir, DEFAULT_KEYSTORE_NAME)
    properties_path = os.path.join(target_dir, DEFAULT_PROPERTIES_NAME)

    if os.path.exists(keystore_path) and not force:
        raise FileExistsError(
            f"Keystore already exists at '{keystore_path}'. Overwrite blocked to protect identity. Use force if intended."
        )

    keytool_bin = find_keytool()
    secure_password = password or secrets.token_urlsafe(32)

    cmd = [
        keytool_bin,
        "-genkeypair",
        "-v",
        "-keystore",
        keystore_path,
        "-storetype",
        "PKCS12",
        "-alias",
        alias,
        "-keyalg",
        "RSA",
        "-keysize",
        str(key_size),
        "-sigalg",
        "SHA256withRSA",
        "-validity",
        str(validity_days),
        "-storepass",
        secure_password,
        "-keypass",
        secure_password,
        "-dname",
        dname,
    ]

    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"keytool failed (exit {proc.returncode}): {proc.stderr}")

    # Format storeFile with forward slashes for Gradle cross-platform compatibility
    normalized_store_file = keystore_path.replace("\\", "/")
    props = {
        "storeFile": normalized_store_file,
        "storePassword": secure_password,
        "keyAlias": alias,
        "keyPassword": secure_password,
    }
    write_properties(properties_path, props)

    fp = get_certificate_fingerprint(keystore_path, secure_password, alias)
    return {
        "success": True,
        "keystore_path": keystore_path,
        "properties_path": properties_path,
        "alias": alias,
        "fingerprint": fp,
    }


def get_certificate_fingerprint(keystore_path: str, store_pass: str, alias: str) -> str:
    """Extract SHA-256 certificate fingerprint from keystore."""
    keytool_bin = find_keytool()
    cmd = [
        keytool_bin,
        "-list",
        "-v",
        "-keystore",
        keystore_path,
        "-storepass",
        store_pass,
        "-alias",
        alias,
    ]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"keytool list failed (exit {proc.returncode}): {proc.stderr}")

    for line in proc.stdout.splitlines():
        line = line.strip()
        if line.startswith("SHA256:") or "SHA-256:" in line:
            parts = line.split(":", 1)[1].strip()
            # Normalize to 64-character lowercase hex without colons
            fp = parts.replace(":", "").replace(" ", "").lower()
            if len(fp) == 64:
                return fp

    raise ValueError("Could not parse SHA-256 certificate fingerprint from keytool output.")


def create_backup(signing_dir: str, backup_zip_path: str) -> dict:
    """Create verified backup zip of keystore and properties with manifest."""
    keystore_path = os.path.join(signing_dir, DEFAULT_KEYSTORE_NAME)
    properties_path = os.path.join(signing_dir, DEFAULT_PROPERTIES_NAME)

    if not os.path.isfile(keystore_path) or not os.path.isfile(properties_path):
        raise FileNotFoundError(f"Signing material missing in '{signing_dir}'.")

    props = read_properties(properties_path)
    fp = get_certificate_fingerprint(keystore_path, props["storePassword"], props["keyAlias"])

    manifest = {
        "version": 1,
        "alias": props["keyAlias"],
        "fingerprint_sha256": fp,
        "files": {
            DEFAULT_KEYSTORE_NAME: sha256_file(keystore_path),
            DEFAULT_PROPERTIES_NAME: sha256_file(properties_path),
        },
    }

    os.makedirs(os.path.dirname(os.path.abspath(backup_zip_path)), exist_ok=True)
    with zipfile.ZipFile(backup_zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(keystore_path, arcname=DEFAULT_KEYSTORE_NAME)
        z.write(properties_path, arcname=DEFAULT_PROPERTIES_NAME)
        z.writestr("backup-manifest.json", json.dumps(manifest, indent=2))

    zip_hash = sha256_file(backup_zip_path)
    with open(backup_zip_path + ".sha256", "w", encoding="utf-8") as f:
        f.write(f"{zip_hash}  {os.path.basename(backup_zip_path)}\n")

    return {
        "success": True,
        "backup_path": backup_zip_path,
        "sha256": zip_hash,
        "fingerprint": fp,
    }


def verify_backup(backup_zip_path: str) -> dict:
    """Verify integrity, manifest, and keytool readability of a backup archive."""
    if not os.path.isfile(backup_zip_path):
        return {"success": False, "error": f"Backup file '{backup_zip_path}' not found."}

    # Verify sha256 file if present
    sha_file = backup_zip_path + ".sha256"
    if os.path.isfile(sha_file):
        with open(sha_file, "r", encoding="utf-8") as f:
            expected_hash = f.read().split()[0].strip().lower()
        actual_hash = sha256_file(backup_zip_path)
        if expected_hash != actual_hash:
            return {"success": False, "error": f"Archive SHA-256 mismatch ({actual_hash} vs {expected_hash})."}

    # Extract to temporary inspection sandbox
    with tempfile.TemporaryDirectory(prefix="tvhome_verify_backup_") as tmp_dir:
        try:
            with zipfile.ZipFile(backup_zip_path, "r") as z:
                if z.testzip() is not None:
                    return {"success": False, "error": "Zip archive CRC test failed."}
                z.extractall(tmp_dir)
        except Exception as e:
            return {"success": False, "error": f"Corrupted zip archive: {e}"}

        manifest_file = os.path.join(tmp_dir, "backup-manifest.json")
        ks_file = os.path.join(tmp_dir, DEFAULT_KEYSTORE_NAME)
        props_file = os.path.join(tmp_dir, DEFAULT_PROPERTIES_NAME)

        if not os.path.isfile(ks_file) or not os.path.isfile(props_file):
            return {"success": False, "error": "Backup archive is missing keystore or properties."}

        props = read_properties(props_file)
        alias = props.get("keyAlias", DEFAULT_ALIAS)
        password = props.get("storePassword")
        if not password:
            return {"success": False, "error": "keystore.properties missing storePassword."}

        try:
            fp = get_certificate_fingerprint(ks_file, password, alias)
        except Exception as e:
            return {"success": False, "error": f"Failed to unlock keystore from backup: {e}"}

        if os.path.isfile(manifest_file):
            with open(manifest_file, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            if manifest.get("fingerprint_sha256") and manifest["fingerprint_sha256"] != fp:
                return {"success": False, "error": "Manifest fingerprint does not match keystore."}

        return {
            "success": True,
            "fingerprint": fp,
            "alias": alias,
        }


def restore_backup(backup_zip_path: str, target_dir: str) -> dict:
    """Restore verified backup into target directory, adjusting paths appropriately."""
    verification = verify_backup(backup_zip_path)
    if not verification.get("success"):
        raise ValueError(f"Cannot restore invalid backup: {verification.get('error')}")

    os.makedirs(target_dir, exist_ok=True)
    with zipfile.ZipFile(backup_zip_path, "r") as z:
        z.extract(DEFAULT_KEYSTORE_NAME, target_dir)
        z.extract(DEFAULT_PROPERTIES_NAME, target_dir)

    # Normalize storeFile path to the restored directory
    props_path = os.path.join(target_dir, DEFAULT_PROPERTIES_NAME)
    props = read_properties(props_path)
    restored_ks_path = os.path.join(target_dir, DEFAULT_KEYSTORE_NAME)
    props["storeFile"] = restored_ks_path.replace("\\", "/")
    write_properties(props_path, props)

    fp = get_certificate_fingerprint(restored_ks_path, props["storePassword"], props["keyAlias"])
    return {
        "success": True,
        "restored_dir": target_dir,
        "fingerprint": fp,
        "alias": props["keyAlias"],
    }


def export_ci_secrets(signing_dir: str, output_file: str) -> dict:
    """Export GitHub Actions secret configuration to a secure local file."""
    keystore_path = os.path.join(signing_dir, DEFAULT_KEYSTORE_NAME)
    properties_path = os.path.join(signing_dir, DEFAULT_PROPERTIES_NAME)

    if not os.path.isfile(keystore_path) or not os.path.isfile(properties_path):
        raise FileNotFoundError(f"Signing material missing in '{signing_dir}'.")

    props = read_properties(properties_path)
    with open(keystore_path, "rb") as f:
        b64_keystore = base64.b64encode(f.read()).decode("ascii")

    os.makedirs(os.path.dirname(os.path.abspath(output_file)), exist_ok=True)
    secrets_map = {
        "RELEASE_KEYSTORE_BASE64": b64_keystore,
        "RELEASE_KEYSTORE_PASSWORD": props["storePassword"],
        "RELEASE_KEY_ALIAS": props["keyAlias"],
        "RELEASE_KEY_PASSWORD": props["keyPassword"],
    }
    write_properties(output_file, secrets_map)

    fp = get_certificate_fingerprint(keystore_path, props["storePassword"], props["keyAlias"])
    return {
        "success": True,
        "output_file": output_file,
        "fingerprint": fp,
    }


def verify_apk_signature(apk_path: str, expected_fingerprint: Optional[str] = None) -> dict:
    """Verify APK signature and certificate fingerprint with apksigner."""
    if not os.path.isfile(apk_path):
        return {"success": False, "error": f"APK file '{apk_path}' not found."}

    apksigner_bin = find_apksigner()
    cmd = [apksigner_bin, "verify", "--verbose", "--print-certs", apk_path]
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if proc.returncode != 0:
        return {"success": False, "error": f"apksigner verification failed: {proc.stderr}"}

    found_fp = None
    for line in proc.stdout.splitlines():
        line = line.strip()
        if "certificate SHA-256 digest:" in line.lower() or "certificate sha-256 digest" in line.lower():
            raw_fp = line.split(":", 1)[1].strip().replace(":", "").replace(" ", "").lower()
            if len(raw_fp) == 64:
                found_fp = raw_fp
                break

    if not found_fp:
        return {"success": False, "error": "Could not extract certificate SHA-256 fingerprint from APK."}

    if expected_fingerprint and found_fp != expected_fingerprint.lower():
        return {
            "success": False,
            "error": f"Certificate fingerprint mismatch ({found_fp} vs expected {expected_fingerprint}).",
            "fingerprint": found_fp,
        }

    return {
        "success": True,
        "fingerprint": found_fp,
    }


def main():
    parser = argparse.ArgumentParser(description="TvHome Launcher Release Signing Identity Manager")
    subparsers = parser.add_subparsers(dest="command", required=True)

    # generate
    p_gen = subparsers.add_parser("generate", help="Generate production keystore")
    p_gen.add_argument("--dir", default=DEFAULT_SIGNING_DIR, help="Target signing directory")
    p_gen.add_argument("--alias", default=DEFAULT_ALIAS, help="Key alias")
    p_gen.add_argument("--force", action="store_true", help="Force overwrite existing keystore")

    # backup
    p_backup = subparsers.add_parser("backup", help="Create verified backup")
    p_backup.add_argument("--dir", default=DEFAULT_SIGNING_DIR, help="Source signing directory")
    p_backup.add_argument("--out", required=True, help="Backup zip destination path")

    # verify-backup
    p_ver_bak = subparsers.add_parser("verify-backup", help="Verify backup archive integrity")
    p_ver_bak.add_argument("--backup", required=True, help="Path to backup zip")

    # restore
    p_res = subparsers.add_parser("restore", help="Restore backup into signing directory")
    p_res.add_argument("--backup", required=True, help="Path to backup zip")
    p_res.add_argument("--dir", default=DEFAULT_SIGNING_DIR, help="Destination directory")

    # fingerprint
    p_fp = subparsers.add_parser("fingerprint", help="Display certificate SHA-256 fingerprint")
    p_fp.add_argument("--dir", default=DEFAULT_SIGNING_DIR, help="Signing directory")

    # export-ci
    p_exp = subparsers.add_parser("export-ci", help="Export CI secrets to a secure local file")
    p_exp.add_argument("--dir", default=DEFAULT_SIGNING_DIR, help="Signing directory")
    p_exp.add_argument("--out", required=True, help="Output env/properties file path")

    # verify-apk
    p_apk = subparsers.add_parser("verify-apk", help="Verify APK signature with apksigner")
    p_apk.add_argument("--apk", required=True, help="Path to APK file")
    p_apk.add_argument("--expected-fp", help="Expected certificate SHA-256 fingerprint")

    args = parser.parse_args()

    try:
        if args.command == "generate":
            res = generate_keystore(target_dir=args.dir, alias=args.alias, force=args.force)
            print(f"Keystore successfully generated.")
            print(f"Directory: {args.dir}")
            print(f"Alias: {res['alias']}")
            print(f"Certificate SHA-256 Fingerprint: {res['fingerprint']}")
        elif args.command == "backup":
            res = create_backup(args.dir, args.out)
            print(f"Backup created successfully: {res['backup_path']}")
            print(f"Archive SHA-256: {res['sha256']}")
            print(f"Certificate SHA-256 Fingerprint: {res['fingerprint']}")
        elif args.command == "verify-backup":
            res = verify_backup(args.backup)
            if res.get("success"):
                print("Backup archive is VALID and readable.")
                print(f"Alias: {res['alias']}")
                print(f"Certificate SHA-256 Fingerprint: {res['fingerprint']}")
            else:
                print(f"Backup verification FAILED: {res.get('error')}", file=sys.stderr)
                sys.exit(1)
        elif args.command == "restore":
            res = restore_backup(args.backup, args.dir)
            print(f"Restored successfully to {res['restored_dir']}")
            print(f"Certificate SHA-256 Fingerprint: {res['fingerprint']}")
        elif args.command == "fingerprint":
            props = read_properties(os.path.join(args.dir, DEFAULT_PROPERTIES_NAME))
            ks_path = os.path.join(args.dir, DEFAULT_KEYSTORE_NAME)
            fp = get_certificate_fingerprint(ks_path, props["storePassword"], props["keyAlias"])
            print(f"Certificate SHA-256 Fingerprint: {fp}")
        elif args.command == "export-ci":
            res = export_ci_secrets(args.dir, args.out)
            print(f"CI secrets exported securely to '{res['output_file']}'.")
            print(f"Certificate SHA-256 Fingerprint: {res['fingerprint']}")
        elif args.command == "verify-apk":
            res = verify_apk_signature(args.apk, args.expected_fp)
            if res.get("success"):
                print("APK signature is VALID.")
                print(f"Certificate SHA-256 Fingerprint: {res['fingerprint']}")
            else:
                print(f"APK signature verification FAILED: {res.get('error')}", file=sys.stderr)
                sys.exit(1)
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
