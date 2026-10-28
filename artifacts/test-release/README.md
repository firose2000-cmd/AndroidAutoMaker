# Advanced 1.1.15 non-debuggable hardened test release

Application source commit: `2fde36345139033506c70b37821be13ddbd5ce2d`.
Variant: `advancedHardenedRelease`. This is a **test build**, not a developer/production release.
It uses a fresh, dedicated RSA test signing identity, not the previous debug key or developer key.

- Package: `com.automaker.app.adv.hardenedtestrelease`
- Version: `1.1.15-security-test-release`
- Compiled `android:debuggable`: **absent**, meaning **false** under release defaults.
  `aapt dump badging` also contains no `application-debuggable` flag.
- APK SHA-256: `42c00a0e4fc2bb8e43a3d4f18cdfa640d72c2849e21c1ada18d0dac832b7a48b`
- Signing certificate SHA-256: `1a36b68d51fe4bc945848ad3b0e82558447a9670399dc5a138f58fd9d142a2b4`

The relevant attribute is `android:debuggable`, not an attribute literally named `android`.
The original debug APK is preserved in `artifacts/security-test`; this new APK has different
bytes, a different checksum, a different package ID, and a different signing certificate.
It can coexist with both the original Maker and the previous hardened debug test build.

## Permissions and Shizuku review

Internet, `READ_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, and `QUERY_ALL_PACKAGES`
remain absent from the compiled APK. There are exactly six requested permissions:

1. `android.permission.POST_NOTIFICATIONS`
2. `android.permission.FOREGROUND_SERVICE`
3. `android.permission.FOREGROUND_SERVICE_DATA_SYNC`
4. `android.permission.REQUEST_INSTALL_PACKAGES`
5. `moe.shizuku.manager.permission.API_V23`
6. `com.automaker.app.adv.hardenedtestrelease.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`

The enabled Shizuku provider is retained for binder delivery. It is exported only behind
`android.permission.INTERACT_ACROSS_USERS_FULL`; it does **not** itself grant shell/root access.
Declaring the API permission does not replace the Shizuku manager's user authorization.
Conversion, saving and the normal Android installer require no Shizuku grant.

| Where | Operations still possible | Required approval and purpose |
| --- | --- | --- |
| Maker's optional Shizuku installer | Open the Android installer through the shell activity-task binder. | User must choose **Use Shizuku** for that installation, allow unknown-source installation, and authorize Maker in Shizuku. A fresh installer helper defaults to **not approved**. Checks at both the operation entry and binder call fail closed. This optional path supports installer compatibility; it does not silently install packages or forge Play Store provenance. |
| Maker's optional expansion restoration | `mkdir` and streamed file writes under `Android/obb/<selected-package>/` or `Android/data/<selected-package>/files/`. | Same explicit per-install approval and Shizuku grant. Every remote process rechecks availability/authorization. Destination checks reject traversal, shell syntax and other packages. Needed only for expansion files supplied in a selected XAPK. Writes can precede installer completion and are not rolled back on cancellation. |
| A converted navigation app (separate app/grant) | Launch/move its task to the virtual display; inspect task/window state; inject mapped `input -d ... tap/swipe` events. | Its own Shizuku-manager consent plus an active navigation session. Shell entry points check the grant again. These existing operations support the virtual-display bridge; Maker's approval does not authorize another package. |

Accessibility and overlay grants remain separate manual Android settings approvals. There are
no automatic accessibility-setting or overlay app-op writes. No new navigation code, DEX asset,
vehicle-motion spoofing or driving-safety bypass was introduced in this correction.

**Remaining risk:** Shizuku grants broad privileges to the entire authorized app. These local
guards cannot narrow the system-level grant or protect against malicious code in a converted
third-party app. Shizuku can remember a grant until revoked; navigation does not request a new
grant on every touch. Revoke unused grants and do not authorize untrusted packages. The inherited
navigation mirror has no independently verified vehicle-motion safety interlock: stationary
testing only. Android 17, USB/wireless Android Auto and vehicle compatibility are unverified.

## GitHub browser download

1. Open the repository's **Actions** tab and select **Download hardened Advanced test APK**.
2. Open the latest run for **security/hardened-advanced-1.1.15**.
3. Wait for success, then download **hardened-advanced-1.1.15-existing-test-release-<run ID>**
   from **Artifacts** on the run's Summary page.
4. Extract it. The ZIP contains the signed APK, `SHA256SUMS`, `build-info.json`,
   `signature-verification.txt`, `compiled-manifest.txt`, `permissions.txt` and these notes.
   The workflow independently verifies the compiled non-debuggable flag and exact permissions
   before uploading. Compare the APK checksum, not the outer ZIP checksum.

The default mode is **existing-test-release**, which copies the verified checked-in APK without
rebuilding. **rebuild-test-release** is an explicit optional alternative; it signs a new build
with a new ephemeral test key and reports that run's actual checksum/certificate fingerprint.
Legacy **existing/rebuild** modes are for the old debuggable variant, not this correction.
No private signing key is committed or uploaded.

If **Run workflow** is unavailable because the workflow is absent from the default branch,
edit `.github/hardened-artifact-request.json` in GitHub on the **security branch only**. Keep
`"mode": "existing-test-release"`, change the request text, and commit directly to that branch.
This triggers a new packaging run without changing master. Artifacts expire after 14 days;
re-run the latest test-release job to recreate one. Do not re-run an older debug-mode job.
Alternatively open the APK file in this directory on GitHub and click **Download raw file**.

Verify locally with `sha256sum -c SHA256SUMS`, or PowerShell:

```powershell
Get-FileHash .\AndroidAutoMaker-Advanced-1.1.15-hardened-existing-test-release.apk -Algorithm SHA256
```

The non-debuggable flag removes the normal Android application debugger attachment surface;
it does not remove Shizuku or make third-party APKs trusted. No real installation, runtime
permission denial/revocation scenario, device instrumentation or car session was tested here.
Automated test totals and lint outcomes are recorded alongside this file in `validation.json`.
