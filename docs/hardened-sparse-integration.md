# Hardened sparse-resource test candidate

PR #1 integrates the previous security hardening with the sparse-resource fix. It remains a draft. No merge, release publication, application installation, device validation or private-key generation was performed.

## Historical implementation located and reviewed

Branch: [`security/hardened-advanced-1.1.15`](https://github.com/firose2000-cmd/AndroidAutoMaker/tree/security/hardened-advanced-1.1.15).

| Commit | Purpose |
| --- | --- |
| `39078d9e5440c6ee6fb69069100ddaeec6b10f42` | Permission reduction, privileged consent and navigation bridge hardening |
| `3f44bf2f3f8cd521d60a9f8ca2225f651bfb31b5` | Existing hardened debug artifact delivery |
| `2fde36345139033506c70b37821be13ddbd5ce2d` | Non-debuggable test release; deny privileged installation without explicit consent |
| `8efce65a1ecae37eba42235fc64ce0f121023677` | Actions artifact delivery and recorded verification |

The historical [`artifacts/test-release/validation.json`](https://github.com/firose2000-cmd/AndroidAutoMaker/blob/8efce65a1ecae37eba42235fc64ce0f121023677/artifacts/test-release/validation.json) records exactly the user-provided package `com.automaker.app.adv.hardenedtestrelease` and certificate SHA-256:

```text
1a36b68d51fe4bc945848ad3b0e82558447a9670399dc5a138f58fd9d142a2b4
```

It attributes application source to `2fde363...`, records `debuggable=false`, and gives historical APK SHA-256 `42c00a0e4fc2bb8e43a3d4f18cdfa640d72c2849e21c1ada18d0dac832b7a48b`. This matches the supplied identity; it is repository provenance, not a new inspection of the installed phone APK. Certificate identity and app debuggability are separate properties.

PR #1 originally branched from `master` (`fc367e3`), which explains why those hardening changes were absent. The reviewed implementation is imported from `8efce65...`; old APK artifacts, Actions upload workflows, fixed test-key passwords and default test-keystore paths are not imported.

## Security comparison

| Area | Original PR baseline | Earlier hardened implementation | Integrated candidate |
| --- | --- | --- | --- |
| Maker permissions | INTERNET, QUERY_ALL_PACKAGES, MANAGE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE declared | All four removed | Same removals; checked in compiled APK |
| Storage and app discovery | Broad storage request; all-package enumeration | SAF documents and visible launchable APK/split export; no private/shared app-data scraping | Same behavior; all original YouTube splits can still be read from installed APK paths |
| Backup/network | allowBackup=false; embedded donation WebView | Backup/transfer exclusions, cleartext disabled, optional donation in external browser | Same source and compiled protections; Maker has no INTERNET permission |
| Installation | Shizuku could be used implicitly; installer attribution manipulation | User selects normal installer or privileged path; explicit consent plus live binder and permission required; no false Play attribution | Same denial, revocation and system-confirmation gates |
| Privileged sidecars | Broad paths and suppressed failures | Validate entire batch; only selected target's Android/obb or Android/data/.../files paths; shell syntax/traversal rejected | Same policy and tests |
| File sharing | Broad file/cache exposure and explicit recipient grants | Only cache/install-share; no global recipient URI grants; revoke temporary access | Same source and compiled FileProvider scope |
| Navigation admission | Arbitrary app could be marked navigation | Require an existing maps/navigation declaration | Same guard; YouTube remains parked-game-only |
| Accessibility/overlay | Shell could enable services or grant overlay automatically | No settings/appops grants; manual Android approval; limited packages/events; no password forwarding | Same source and exact historical navigation DEX |
| Navigation privilege | Global hidden-API exemption and allow-all host validator | No global exemption; pinned Android Auto host certificates; active session and Shizuku grant required per privileged command | Same controls and bundled asset hashes |
| Archive/process lifecycle | Unbounded expansion and Thread.stop | 100,000 entries / 4 GiB actual expanded-byte ceiling; cooperative interruption and FGS timeout cleanup | Same controls; original sparse correction retained independently |
| Maker test identity | Normal Advanced ID | `.adv.hardenedtestrelease` | New `.adv.hardenedsparseresourcetest`; non-debuggable; separate label and output |
| Signing | Normal release keystore configuration | Separate test key, but fixed default path/password/alias in build configuration | No default candidate keystore, password or debug fallback; external existing key only, pinned to supplied certificate |
| Converted package identity | Optional `.patched` | Optional `.patched` | Candidate forces `.patched` in both UI and session creation, avoiding original target ID |

The compiled candidate requests exactly six permissions:

```text
android.permission.POST_NOTIFICATIONS
android.permission.FOREGROUND_SERVICE
android.permission.FOREGROUND_SERVICE_DATA_SYNC
android.permission.REQUEST_INSTALL_PACKAGES
moe.shizuku.manager.permission.API_V23
com.automaker.app.adv.hardenedsparseresourcetest.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

The last two are dependency declarations retained from the hardened implementation. The dynamic-receiver permission is signature-protected. `INTERACT_ACROSS_USERS_FULL` protects the exported Shizuku provider; Maker does not request that permission. The only other exported components are the launcher and dependency ProfileInstallReceiver, whose access is guarded by `android.permission.DUMP`. Secondary activities, the conversion service, install callback and FileProvider remain non-exported.

These are **Maker** permissions. Original input-app permissions, including YouTube's network permission, are preserved in converted parked apps. Optional navigation conversion retains its existing sensitive declarations but never automatically grants them. The parked-game path does not add the navigation bridge or its sensitive permissions.

## Exact integration scope

Historical source imported: main manifest; DonationActivity; MainActivity; Sessions; InstalledApps; ManifestPatcher; XapkPackages; AttributedInstaller; BundleInstall; InstallPolicy; InstallResultReceiver; PlayStoreInstall; strings; FileProvider paths; backup/transfer rules; NavigationCarService and TouchBridge; ArchiveLimitsTest, SecurityManifestTest and InstallPolicyTest. ConverterTest combines the historical navigation/security assertions with PR #1's export regression.

Both bundled navigation DEX copies are the exact historical hardened blob, SHA-256:

```text
e88b1391fbafa7f590ed25a61e4702a38257d48e85e5eba73c3108d13d4dfd32
```

`PackageConverter.kt` and desktop `MergeXapk.java` retain the compact ascending sparse-index correction. Their resource-repair code was not overwritten by the hardening import. No vehicle-motion restriction was relaxed.

New configuration: `android/app/build.gradle.kts` defines `advancedHardenedSparseRelease`; `src/hardenedSparseRelease/res/values/security_test.xml` provides label and mandatory side-by-side policy. `MainActivity` reflects that policy and `Sessions` enforces it independently. `android/gradle.properties` no longer forces a fixed Windows JDK directory; use JDK 17 through JAVA_HOME. Generated Kotlin/Python caches are ignored.

New read-only verifier and negative tests: `scripts/verify_hardened_sparse_apk.py`, `scripts/test_verify_hardened_sparse_apk.py`. No keystore or historical APK is added to the PR.

## Results and remaining limits

- Candidate, Advanced Debug and Standard Debug suites: **18 passed each**, zero failures/errors/skips, with supplied YouTube diagnostics enabled.
- Exact-table Android and desktop regressions still preserve **35,377 records**, **135 valid sparse chunks**, and a **2,432,740-byte** resource table.
- `assembleAdvancedHardenedSparseRelease`: passed, producing an **unsigned** candidate. No fallback signing key was generated.
- Read-only compiled APK audit: passed for isolated ID, non-debuggable flag, exact permission inventory, backup/cleartext policy, guarded exports, unique provider authorities, restricted sharing, forced `.patched`, and exact hardened bridge asset.
- Artifact policy negative tests: **8 passed**, rejecting removed permissions, old Maker package identity, debug flags, unguarded/extra exported components, broad sharing and non-side-by-side conversion.
- Signing guard: correctly rejected the existing unrelated fixture certificate. The signed assembly task graph includes this guard before signing validation. The positive path with the actual hardened private key is **not exercised**, because that key is unavailable here.
- Full candidate lint: **0 errors, 11 warnings** (5 unused resources, 2 hardcoded text, and one each for SdCardPath, ObsoleteSdkInt, MonochromeLauncherIcon, SetTextI18n).
- Node tests and `git diff --check`: passed.

No known historical permission/consent control is intentionally omitted. Remaining risks/compatibility limits from the hardened baseline include privileged Shizuku/private Android API behavior, manually enabled navigation window-content access, bundled dependency provenance outside the pinned bridge, and platform/head-unit compatibility. Runtime denial/revocation, phone playback and motion gating have not been tested on a device. Static tests do not establish Android Auto certification.

The new Maker package has a separate Android Keystore namespace. Its converted `.patched` APK may have a different signer from a previously converted copy, even when both Maker APKs use the same certificate. An existing `.patched` installation may therefore reject an update. Preserve the original YouTube and previous Maker; do not uninstall either. Any decision about removing an old converted clone is a separate user action, not part of this build procedure.

Legacy normal/debug variants keep their original signing behavior; the isolated candidate does not use those signing defaults. Do not confuse passing debug unit tests with building a non-debuggable APK. Only the candidate target below is recommended for this investigation.

## Safest Windows / JDK 17 build

Use a separate checkout of PR #1's branch, keeping prior APKs and keys outside it. Prerequisites: JDK 17, Android SDK platform 36 and build-tools 35 or newer, Python 3 for the read-only verifier. Use trusted dependency downloads and normal TLS verification; do not disable lint or certificate checks. No device command or installation is needed.

In PowerShell at the repository root, adjust the JDK and diagnostic paths:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
& "$env:JAVA_HOME\bin\java.exe" -version
git rev-parse HEAD

# Optional: set to the folder containing the five extracted diagnostics.
$env:YOUTUBE_RESOURCE_TABLES = 'C:\Private\YouTube-diagnostics'

# For the first inspection, ensure candidate-signing variables are absent.
Remove-Item Env:AUTOMAKER_HARDENED_KEYSTORE, Env:AUTOMAKER_HARDENED_STORE_PASSWORD, `
  Env:AUTOMAKER_HARDENED_KEY_ALIAS, Env:AUTOMAKER_HARDENED_KEY_PASSWORD -ErrorAction SilentlyContinue

.\android\gradlew.bat -p android :app:testAdvancedHardenedSparseReleaseUnitTest `
  :app:testAdvancedDebugUnitTest :app:testStandardDebugUnitTest `
  :app:lintAdvancedHardenedSparseRelease :app:assembleAdvancedHardenedSparseRelease
if ($LASTEXITCODE -ne 0) { throw 'Build or checks failed' }

$apk = '.\android\app\build\outputs\apk\advanced\hardenedSparseRelease\AndroidAutoMaker-Advanced-1.1.15-hardened-sparse-test-HardenedSparseTest.apk'
py -3 .\scripts\verify_hardened_sparse_apk.py $apk --unsigned
if ($LASTEXITCODE -ne 0) { throw 'Unsigned candidate policy check failed' }
```

If the diagnostics are unavailable, unset YOUTUBE_RESOURCE_TABLES; its optional integration test will skip while synthetic and security tests run. An unsigned APK is an inspection artifact, not an installable approved test build.

For a signed candidate, reuse the **existing** external hardened-test keystore only if its private key is available. A fingerprint or certificate alone cannot sign an APK. If the key is unavailable, stop at the unsigned inspection; do not generate a replacement. Configure these four environment variables in a trusted local PowerShell session:

```powershell
$env:AUTOMAKER_HARDENED_KEYSTORE = 'C:\Private\existing-hardened-test.keystore'
$env:AUTOMAKER_HARDENED_KEY_ALIAS = Read-Host 'Existing key alias'

function Read-SigningPassword([string]$Prompt) {
  $secure = Read-Host $Prompt -AsSecureString
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

try {
  $env:AUTOMAKER_HARDENED_STORE_PASSWORD = Read-SigningPassword 'Existing store password'
  $env:AUTOMAKER_HARDENED_KEY_PASSWORD = Read-SigningPassword 'Existing key password'
  .\android\gradlew.bat -p android :app:assembleAdvancedHardenedSparseRelease
  if ($LASTEXITCODE -ne 0) { throw 'Signed candidate build failed' }
  # No --unsigned: requires a valid APK signature and the exact supplied certificate.
  py -3 .\scripts\verify_hardened_sparse_apk.py $apk
  if ($LASTEXITCODE -ne 0) { throw 'Signed candidate verification failed' }
} finally {
  Remove-Item Env:AUTOMAKER_HARDENED_KEYSTORE, Env:AUTOMAKER_HARDENED_STORE_PASSWORD, `
    Env:AUTOMAKER_HARDENED_KEY_ALIAS, Env:AUTOMAKER_HARDENED_KEY_PASSWORD -ErrorAction SilentlyContinue
}
```

Passwords are transient environment values for Gradle, not command-line literals or committed files. Use the verifier's signed result to record the APK hash and exact certificate. The Gradle signing guard rejects a certificate different from `1a36b68d...42a2b4`; there is no debug fallback.

Expected candidate identity:

```text
com.automaker.app.adv.hardenedsparseresourcetest
AA Maker Hardened Sparse Test
1.1.15-hardened-sparse-test
debuggable=false
```

This identity cannot update the installed `com.automaker.app.adv.hardenedtestrelease`, normal Advanced Maker or original YouTube. All candidate conversions force `.patched`. No command above installs, uninstalls, grants special access, publishes or merges anything. Device validation remains a separate step after reviewing the signed artifact.
