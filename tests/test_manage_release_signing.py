import os
import sys
import shutil
import tempfile
import unittest
from pathlib import Path

# Add project root to sys.path
REPO_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPO_ROOT))

from tools import manage_release_signing


class TestManageReleaseSigning(unittest.TestCase):
    def setUp(self):
        self.test_dir = tempfile.mkdtemp(prefix="tvhome_signing_test_")
        self.signing_dir = os.path.join(self.test_dir, "tvhome-launcher-signing")
        self.backup_dir = os.path.join(self.test_dir, "backups")
        os.makedirs(self.backup_dir, exist_ok=True)

    def tearDown(self):
        shutil.rmtree(self.test_dir, ignore_errors=True)

    def test_find_keytool_and_apksigner(self):
        keytool_bin = manage_release_signing.find_keytool()
        self.assertTrue(os.path.isfile(keytool_bin), f"keytool not found: {keytool_bin}")
        apksigner_bin = manage_release_signing.find_apksigner()
        self.assertTrue(os.path.isfile(apksigner_bin), f"apksigner not found: {apksigner_bin}")

    def test_generate_keystore_creates_valid_keystore_and_properties(self):
        result = manage_release_signing.generate_keystore(
            target_dir=self.signing_dir,
            alias="tvhome-release",
            dname="CN=TvHome Release, OU=TvHome Launcher, O=Flexy01, L=Istanbul, C=TR",
            key_size=4096,
            validity_days=10000,
        )
        self.assertTrue(result["success"])
        keystore_path = result["keystore_path"]
        properties_path = result["properties_path"]
        self.assertTrue(os.path.isfile(keystore_path))
        self.assertTrue(os.path.isfile(properties_path))

        # Check properties content
        props = manage_release_signing.read_properties(properties_path)
        self.assertEqual(props.get("keyAlias"), "tvhome-release")
        self.assertTrue(len(props.get("storePassword", "")) >= 24)
        self.assertTrue(len(props.get("keyPassword", "")) >= 24)
        self.assertEqual(props.get("storePassword"), props.get("keyPassword"))

        # Verify keystore can be opened by keytool
        fp = manage_release_signing.get_certificate_fingerprint(keystore_path, props["storePassword"], "tvhome-release")
        self.assertEqual(len(fp), 64, f"Fingerprint must be 64-character SHA-256 hex string: {fp}")

        # Refuse to overwrite without force
        with self.assertRaises(FileExistsError):
            manage_release_signing.generate_keystore(target_dir=self.signing_dir, alias="tvhome-release")

    def test_backup_and_verification_workflow(self):
        # First generate keystore
        gen = manage_release_signing.generate_keystore(target_dir=self.signing_dir)
        keystore_path = gen["keystore_path"]
        props = manage_release_signing.read_properties(gen["properties_path"])
        orig_fp = manage_release_signing.get_certificate_fingerprint(keystore_path, props["storePassword"], props["keyAlias"])

        # Create backup
        backup_path = os.path.join(self.backup_dir, "test-backup.zip")
        backup_res = manage_release_signing.create_backup(self.signing_dir, backup_path)
        self.assertTrue(backup_res["success"])
        self.assertTrue(os.path.isfile(backup_path))
        self.assertTrue(os.path.isfile(backup_path + ".sha256"))

        # Verify valid backup
        verify_res = manage_release_signing.verify_backup(backup_path)
        self.assertTrue(verify_res["success"])
        self.assertEqual(verify_res["fingerprint"], orig_fp)

        # Tampered backup must fail verification
        tampered_backup = os.path.join(self.backup_dir, "tampered-backup.zip")
        shutil.copyfile(backup_path, tampered_backup)
        with open(tampered_backup, "r+b") as f:
            f.seek(50)
            f.write(b"CORRUPTED_BYTES")

        tamper_verify = manage_release_signing.verify_backup(tampered_backup)
        self.assertFalse(tamper_verify["success"])

    def test_restore_workflow(self):
        gen = manage_release_signing.generate_keystore(target_dir=self.signing_dir)
        keystore_path = gen["keystore_path"]
        props = manage_release_signing.read_properties(gen["properties_path"])
        orig_fp = manage_release_signing.get_certificate_fingerprint(keystore_path, props["storePassword"], props["keyAlias"])

        backup_path = os.path.join(self.backup_dir, "test-backup.zip")
        manage_release_signing.create_backup(self.signing_dir, backup_path)

        # Simulate complete loss of live signing directory
        shutil.rmtree(self.signing_dir)
        self.assertFalse(os.path.exists(self.signing_dir))

        # Restore
        restore_res = manage_release_signing.restore_backup(backup_path, self.signing_dir)
        self.assertTrue(restore_res["success"])
        self.assertEqual(restore_res["fingerprint"], orig_fp)

        # Verify restored directory works
        restored_props = manage_release_signing.read_properties(os.path.join(self.signing_dir, "keystore.properties"))
        restored_fp = manage_release_signing.get_certificate_fingerprint(
            os.path.join(self.signing_dir, "tvhome-release.keystore"),
            restored_props["storePassword"],
            restored_props["keyAlias"],
        )
        self.assertEqual(restored_fp, orig_fp)

    def test_export_ci_secrets_payload(self):
        gen = manage_release_signing.generate_keystore(target_dir=self.signing_dir)
        secrets_file = os.path.join(self.signing_dir, "github-secrets.env")
        export_res = manage_release_signing.export_ci_secrets(self.signing_dir, secrets_file)
        self.assertTrue(export_res["success"])
        self.assertTrue(os.path.isfile(secrets_file))

        # Verify secrets file content format
        secrets_props = manage_release_signing.read_properties(secrets_file)
        self.assertIn("RELEASE_KEYSTORE_BASE64", secrets_props)
        self.assertIn("RELEASE_KEYSTORE_PASSWORD", secrets_props)
        self.assertIn("RELEASE_KEY_ALIAS", secrets_props)
        self.assertIn("RELEASE_KEY_PASSWORD", secrets_props)

        import base64
        decoded = base64.b64decode(secrets_props["RELEASE_KEYSTORE_BASE64"])
        with open(gen["keystore_path"], "rb") as f:
            orig_bytes = f.read()
        self.assertEqual(decoded, orig_bytes)


if __name__ == "__main__":
    unittest.main()
