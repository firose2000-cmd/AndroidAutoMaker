# Dedicated certificate for the isolated sparse test candidate

This supersedes commit `6313c39`'s candidate-only restriction to the old hardened certificate. It does not change the original hardened Maker identity or its certificate pin.

## Scope and policy

`config/signing-policy.json` is the public identity policy used by Gradle and the read-only verifier:

| Identity/scope | Accepted signer |
| --- | --- |
| Exactly `advancedHardenedSparseRelease` AND `com.automaker.app.adv.hardenedsparseresourcetest` | Explicitly pinned dedicated test certificate, or the previous hardened certificate if no dedicated pin is provided |
| Any build retaining `com.automaker.app.adv.hardenedtestrelease` | Original certificate only: `1a36b68d51fe4bc945848ad3b0e82558447a9670399dc5a138f58fd9d142a2b4` |
| Other guarded variants, including Standard hardened-sparse | Dedicated test pin is rejected; the original pin is not overridden |

The dedicated opt-in is `AUTOMAKER_SPARSE_TEST_CERT_SHA256`. It must be the SHA-256 of the certificate exported from the selected keystore, determined before building or reading the APK. Colon-separated fingerprints are accepted and normalized. A different or malformed fingerprint fails closed. The verifier never trusts the APK's own fingerprint as its expected value.

The four credential variable names are unchanged for compatibility: `AUTOMAKER_HARDENED_KEYSTORE`, `AUTOMAKER_HARDENED_STORE_PASSWORD`, `AUTOMAKER_HARDENED_KEY_ALIAS`, `AUTOMAKER_HARDENED_KEY_PASSWORD`. All four must be explicitly supplied, or all absent for an unsigned inspection build. There is no automatic key generation, embedded test password or debug-key fallback. No original-identity build type is added; any variant resolving to that original application ID is guarded if such a variant is configured in future.

The old normal-release hardcoded keystore path, passwords and alias are also removed. Release variants use explicit external credentials or remain unsigned. Every variant using that external signing configuration is guarded: the dedicated opt-in is rejected outside the exact Advanced candidate. Ordinary debug variants remain development variants and are never a signing fallback for the candidate.

The dedicated key is for the isolated Maker test package, not production or the original hardened Maker. All permission, non-debuggable, forced `.patched`, Accessibility/overlay/Shizuku and motion restrictions remain unchanged. Creating a different Maker certificate does not change the package identity, so later updates of this isolated candidate must reuse its dedicated key.

## Windows PowerShell / JDK 17

Run these commands manually in a trusted local PowerShell session at the PR checkout root. Adjust the JDK path to the installed JDK 17 folder. Keep the keystore outside the repository. Nothing here installs/uninstalls an app, grants privileges, merges the PR or publishes a release.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17'
$env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
& "$env:JAVA_HOME\bin\java.exe" -version
git rev-parse HEAD

$keyDir = Join-Path $env:LOCALAPPDATA 'AndroidAutoMaker\signing'
New-Item -ItemType Directory -Force $keyDir | Out-Null
$keystore = Join-Path $keyDir 'hardened-sparse-test.p12'
$certificate = Join-Path $keyDir 'hardened-sparse-test.cer'
$alias = 'automaker-sparse-test'
```

Create the dedicated key **once**, as an explicit operator action. `keytool` prompts for the password; no password is embedded in the command. If the file already exists, do not overwrite it: reuse the existing dedicated key after verifying its certificate.

```powershell
if (Test-Path $keystore) { throw 'Keystore already exists. Reuse and verify it; do not overwrite.' }
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -storetype PKCS12 `
  -keystore $keystore -alias $alias -keyalg RSA -keysize 3072 `
  -sigalg SHA256withRSA -validity 3650 `
  -dname 'CN=AndroidAutoMaker Hardened Sparse Test,OU=Dedicated Test,O=Local Test'
if ($LASTEXITCODE -ne 0) { throw 'Dedicated key creation failed' }
```

Export only the public certificate in DER format (do not add `-rfc`). Its file hash is the certificate fingerprint, independent of any built APK:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -exportcert -keystore $keystore `
  -alias $alias -file $certificate
if ($LASTEXITCODE -ne 0) { throw 'Certificate export failed' }
$fingerprint = (Get-FileHash -Algorithm SHA256 $certificate).Hash.ToLowerInvariant()
Write-Host "Dedicated test certificate SHA-256: $fingerprint"
```

Keep the private keystore/password backed up privately. The `.cer` file is public certificate material, not a private key. Do not commit the keystore or password. The build does not run the key-creation commands itself.

Read the password without placing it in command history. For the PKCS12 key created above, use the same key/store password:

```powershell
function Read-SigningPassword([string]$Prompt) {
  $secure = Read-Host $Prompt -AsSecureString
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

try {
  $env:AUTOMAKER_HARDENED_KEYSTORE = $keystore
  $env:AUTOMAKER_HARDENED_KEY_ALIAS = $alias
  $env:AUTOMAKER_HARDENED_STORE_PASSWORD = Read-SigningPassword 'Dedicated PKCS12 password'
  $env:AUTOMAKER_HARDENED_KEY_PASSWORD = $env:AUTOMAKER_HARDENED_STORE_PASSWORD
  $env:AUTOMAKER_SPARSE_TEST_CERT_SHA256 = $fingerprint

  # Optional: enable the exact-table integration test if these local files exist.
  # $env:YOUTUBE_RESOURCE_TABLES = 'C:\Private\YouTube-diagnostics'
  .\android\gradlew.bat -p android :app:testAdvancedHardenedSparseReleaseUnitTest `
    :app:lintAdvancedHardenedSparseRelease :app:assembleAdvancedHardenedSparseRelease
  if ($LASTEXITCODE -ne 0) { throw 'Candidate build/tests failed' }

  $apk = '.\android\app\build\outputs\apk\advanced\hardenedSparseRelease\AndroidAutoMaker-Advanced-1.1.15-hardened-sparse-test-HardenedSparseTest.apk'
  py -3 .\scripts\verify_hardened_sparse_apk.py $apk --expected-cert-sha256 $fingerprint
  if ($LASTEXITCODE -ne 0) { throw 'Signed APK identity/certificate/security verification failed' }
  py -3 -m unittest discover -s scripts -p 'test_*.py'
  if ($LASTEXITCODE -ne 0) { throw 'Signing/artifact policy tests failed' }
} finally {
  Remove-Item Env:AUTOMAKER_HARDENED_KEYSTORE, Env:AUTOMAKER_HARDENED_STORE_PASSWORD, `
    Env:AUTOMAKER_HARDENED_KEY_ALIAS, Env:AUTOMAKER_HARDENED_KEY_PASSWORD, `
    Env:AUTOMAKER_SPARSE_TEST_CERT_SHA256 -ErrorAction SilentlyContinue
}
```

The verifier must report `signature_verification: passed`, the exact candidate ID, the independent fingerprint, the six-permission inventory and `debuggable: false`. Omitting `--unsigned` is intentional. The signed APK still requires device validation; do not treat signing verification as launch/playback testing.

## Original hardened Maker verification remains fixed

With the dedicated pin environment variable cleared, an existing original-identity APK can be checked read-only:

```powershell
Remove-Item Env:AUTOMAKER_SPARSE_TEST_CERT_SHA256 -ErrorAction SilentlyContinue
py -3 .\scripts\verify_hardened_sparse_apk.py 'C:\Private\existing-hardened-Maker.apk' `
  --identity existing-hardened
if ($LASTEXITCODE -ne 0) { throw 'Original hardened Maker verification failed' }
```

This checks `com.automaker.app.adv.hardenedtestrelease` against the original fixed certificate, permissions and non-debuggable protections. Supplying a different expected certificate cannot override that pin. It does not modify the APK or installed application. The candidate-only forced `.patched` setting is not required of the historical APK.

## Validation for this change

- Signing/artifact policy tests: **18 passed**, covering dedicated acceptance only in the exact scope, missing/wrong pins, other variants/packages, original pin preservation and compiled security rejections.
- A candidate APK was signed through Gradle using the **existing repository fixture key** plus its explicitly supplied fingerprint. Full APK signature, candidate package, fingerprint, six permissions, non-debuggable state and hardened asset verification passed. This exercises a certificate different from the historical pin without generating a new key. This fixture-signed APK is not a dedicated release deliverable and is not published.
- Actual Gradle gates reject the fixture certificate without its candidate opt-in and reject that opt-in for both Standard and ordinary Advanced Release. Positive original-key signing is not tested because its private key is unavailable.
- Candidate Android security/resource tests: **18 passed**, zero failures/errors/skips, rerun with the supplied resource tables. They still preserve 35,377 records and 135 valid sparse indexes. Signed assembly passes; full candidate lint has **zero errors and 11 warnings**. No security/resource implementation or bundled bridge asset is changed by this signing-policy commit.

No private key or certificate generated by a user is committed. No application was installed, no existing Maker was altered, and no PR merge, release publication or device validation was performed.
