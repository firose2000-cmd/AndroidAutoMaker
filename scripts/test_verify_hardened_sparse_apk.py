"""Negative checks for the compiled-artifact verification policy; no APK is modified."""
import unittest
import verify_hardened_sparse_apk as audit


class ArtifactPolicyTest(unittest.TestCase):
    def setUp(self):
        self.badging = f"package: name='{audit.PACKAGE}' versionCode='17'"
        self.permissions = "\n".join(f"uses-permission: name='{p}'" for p in audit.PERMISSIONS)
        self.manifest = f'''E: manifest
  E: application
    A: android:allowBackup(0x01)=false
    A: android:fullBackupContent(0x02)=false
    A: android:usesCleartextTraffic(0x03)=false
    A: android:dataExtractionRules(0x04)=@0x7f010000
    E: provider
      A: android:name(0x05)="androidx.core.content.FileProvider"
      A: android:authorities(0x06)="{audit.PACKAGE}.files"
      A: android:exported(0x07)=false
    E: provider
      A: android:name(0x05)="rikka.shizuku.ShizukuProvider"
      A: android:authorities(0x06)="{audit.PACKAGE}.shizuku"
      A: android:exported(0x07)=true
      A: android:permission(0x08)="android.permission.INTERACT_ACROSS_USERS_FULL"
'''
        self.paths = 'E: paths\n  E: cache-path\n    A: path="install-share/"\n'
        self.resources = 'resource 0x7f010000 bool/force_side_by_side\n  () true\n'

    def inspect(self):
        audit.inspect(self.badging, self.permissions, self.manifest, self.paths, self.resources)

    def test_accepts_expected_static_policy(self):
        self.inspect()

    def test_rejects_each_removed_permission(self):
        for permission in ("INTERNET", "QUERY_ALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE"):
            with self.subTest(permission=permission), self.assertRaises(ValueError):
                audit.inspect(self.badging, self.permissions + f"\nuses-permission: name='android.permission.{permission}'",
                              self.manifest, self.paths, self.resources)

    def test_rejects_existing_hardened_package_identity(self):
        self.badging = "package: name='com.automaker.app.adv.hardenedtestrelease'"
        with self.assertRaises(ValueError):
            self.inspect()

    def test_rejects_debuggable_from_either_compiled_source(self):
        for badging, manifest in ((self.badging + "\napplication-debuggable", self.manifest),
                                  (self.badging, self.manifest.replace("  E: application", "  E: application\n    A: android:debuggable(0x09)=true"))):
            with self.subTest(badging=badging), self.assertRaises(ValueError):
                audit.inspect(badging, self.permissions, manifest, self.paths, self.resources)

    def test_rejects_unguarded_shizuku_provider(self):
        self.manifest = self.manifest.replace("android.permission.INTERACT_ACROSS_USERS_FULL", "android.permission.INTERNET")
        with self.assertRaises(ValueError):
            self.inspect()

    def test_rejects_additional_exported_component(self):
        self.manifest += '    E: service\n      A: android:name="example.Exposed"\n      A: android:exported=true\n'
        with self.assertRaises(ValueError):
            self.inspect()

    def test_rejects_wide_cache_sharing(self):
        self.paths = self.paths.replace("install-share/", ".")
        with self.assertRaises(ValueError):
            self.inspect()

    def test_rejects_original_package_conversion_mode(self):
        self.resources = self.resources.replace("() true", "() false")
        with self.assertRaises(ValueError):
            self.inspect()


if __name__ == "__main__":
    unittest.main()
