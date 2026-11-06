import unittest
from signing_policy import DEDICATED, ORIGINAL, expected_certificate, fingerprint, verify_certificate


class SigningPolicyTest(unittest.TestCase):
    dedicated = "ab" * 32

    def test_dedicated_certificate_requires_exact_candidate_scope(self):
        self.assertEqual(self.dedicated, verify_certificate(DEDICATED["application_id"], DEDICATED["variant"], self.dedicated, self.dedicated))

    def test_new_certificate_without_explicit_pin_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_certificate(DEDICATED["application_id"], DEDICATED["variant"], self.dedicated)

    def test_wrong_candidate_certificate_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_certificate(DEDICATED["application_id"], DEDICATED["variant"], "cd" * 32, self.dedicated)

    def test_other_variants_cannot_use_dedicated_pin(self):
        for variant in ("standardHardenedSparseRelease", "advancedRelease", "advancedDebug"):
            with self.subTest(variant=variant), self.assertRaises(ValueError):
                expected_certificate(DEDICATED["application_id"], variant, self.dedicated)

    def test_other_packages_cannot_use_dedicated_pin(self):
        for package in (ORIGINAL["application_id"], "com.google.android.youtube", "com.automaker.app.adv"):
            with self.subTest(package=package), self.assertRaises(ValueError):
                expected_certificate(package, DEDICATED["variant"], self.dedicated)

    def test_original_identity_always_requires_original_certificate(self):
        original = ORIGINAL["certificate_sha256"]
        self.assertEqual(original, verify_certificate(ORIGINAL["application_id"], "advancedHardenedRelease", original))
        with self.assertRaises(ValueError):
            verify_certificate(ORIGINAL["application_id"], "advancedHardenedRelease", self.dedicated)
        with self.assertRaises(ValueError):
            verify_certificate(ORIGINAL["application_id"], "advancedHardenedRelease", self.dedicated, self.dedicated)

    def test_existing_candidate_signer_remains_backward_compatible(self):
        original = ORIGINAL["certificate_sha256"]
        self.assertEqual(original, verify_certificate(DEDICATED["application_id"], DEDICATED["variant"], original))

    def test_fingerprint_normalization_and_invalid_values(self):
        self.assertEqual(self.dedicated, fingerprint(":".join(["AB"] * 32)))
        for invalid in ("", "abc", "gg" * 32, "0" * 65):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                fingerprint(invalid)


if __name__ == "__main__":
    unittest.main()
