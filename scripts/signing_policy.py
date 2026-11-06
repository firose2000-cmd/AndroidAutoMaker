"""Shared public policy for external signing and read-only APK verification."""
import json
from pathlib import Path
import re

POLICY = json.loads((Path(__file__).resolve().parents[1] / "config/signing-policy.json").read_text())
ORIGINAL = POLICY["original_hardened"]
DEDICATED = POLICY["dedicated_test"]


def fingerprint(value):
    normalized = value.strip().replace(":", "").lower()
    if not re.fullmatch(r"[0-9a-f]{64}", normalized):
        raise ValueError("Expected a SHA-256 certificate fingerprint")
    return normalized


def expected_certificate(application_id, variant, dedicated=None):
    dedicated = fingerprint(dedicated) if dedicated is not None else None
    if application_id == DEDICATED["application_id"] and variant == DEDICATED["variant"]:
        return dedicated or ORIGINAL["certificate_sha256"]
    if dedicated is not None and dedicated != ORIGINAL["certificate_sha256"]:
        raise ValueError("A dedicated test certificate cannot override another variant or the original hardened identity")
    return ORIGINAL["certificate_sha256"]


def verify_certificate(application_id, variant, actual, dedicated=None):
    actual = fingerprint(actual)
    if actual != expected_certificate(application_id, variant, dedicated):
        raise ValueError("Signing certificate differs from the expected fingerprint for this identity")
    return actual
