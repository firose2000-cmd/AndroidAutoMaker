# Android Auto Maker Advanced 1.1.15 security hardening

Baseline: `fc367e32181d59965a6d0f9bdfb89574bd1fb070` (original developer source).
Branch: `security/hardened-advanced-1.1.15`. Prepared 28 October 2026.

This is an experimental, separately signed debug/test build. It is not a production release,
an independent penetration-test certification, or a claim of safe vehicle operation.
No phone was connected, installed to, or modified. No branch was merged or release published.
Original APK files present in the workspace were inventoried and checked for changes.

## Verified deliverable

- Advanced debug build, all four Android unit-test variants, Advanced debug lint and
  instrumentation APK compilation succeeded. Unit tests: **13 per variant, 52 passes total,
  zero failures/skips**. Node tests: **2 passes**. Lint: **zero errors, 11 warnings** (storage
  shell path, obsolete SDK guard, unused resources, icon and UI text warnings).
- APK: `AndroidAutoMaker-Advanced-1.1.15-security-hardened-test.apk`, 11,194,734 bytes.
- APK SHA-256: `503f3a3f6c581a871db5f032f561a8881fe8a70b638bba459993d4dc29311705`.
- Signing certificate SHA-256:
  `8f19dd6ed340086cf4d701841dab913170a7add143f15c0987556e285cb5bf4d`.
  Signature verification succeeded, and the certificate differs from the retained baseline
  Standard debug APK. The original publisher APK/certificate was not supplied for comparison.
- Binary permission extraction matched exactly the six requested permissions documented below;
  FileProvider's packaged path is the isolated install cache; packaged navigation DEX matches
  the rebuilt hardened asset; no private keystore/password files are embedded.
- Four pre-existing workspace APK files retained their original SHA-256 hashes.
- Device instrumentation and real installation/car compatibility **were not run**. The device
  test previously lacked its `sample.apk` asset; a reproducibly generated synthetic fixture is
  now included so that test can be run later on an explicitly authorized test device/emulator.

## Audit and implemented changes

| Area | Baseline finding | Hardened behavior |
| --- | --- | --- |
| Storage | All-files access requested automatically when exporting an installed app; legacy storage permission also present. | Both storage permissions and the settings escalation removed. Document-picker imports/exports remain. Installed-app export copies visible base/split APKs only; import a user-selected XAPK to include expansion files. No other app's private data is exported. |
| Package visibility | `QUERY_ALL_PACKAGES` and enumeration of installed applications. | Launcher-intent query and launcher activity enumeration only, with explicit Shizuku-manager visibility and installer intent discovery. Removed unnecessary Play Store/Google-installer package queries. Export rechecks that the selected app is launchable. |
| Network | Embedded checkout WebView enabled JavaScript, DOM storage and third-party cookies, with unrestricted app Internet permission. | Removed Maker's Internet permission and WebView execution. Optional donation link opens the original fixed HTTPS Stripe URL in the user's external browser. Conversion needs no network. Cleartext traffic explicitly disabled. |
| Privileged installation | Existing Shizuku grants used automatically; dead bundle path could commit shell sessions; installer identity was spoofed as Google Play, including verification UID 0. | Explicit per-install choice of Android installer or Shizuku. Recheck unknown-source permission and Shizuku consent. Removed silent shell bundle installation and Play attribution mutation/extras. Normal bundle API still explicitly requires system user action. |
| Expansion files | Privileged copy could accept another package's `Android/` files and fall back to direct external-storage writes. | No direct broad-storage fallback. Validate the entire batch before writing. Only `Android/obb/<converted-package>/...` and `Android/data/<converted-package>/files/...` accepted; reject traversal, other packages, empty components and shell syntax. |
| URI sharing | FileProvider exposed the sessions tree and pregranted several packages. | Share only a staged APK in a dedicated cache directory. Normal installation uses the intent's read grant; shell receives a grant only after the explicit Shizuku choice. Revoke the APK URI and delete its staged copy when monitoring ends/cancels. APK staging runs off the UI thread. |
| Shizuku navigation bridge | Automatically wrote secure accessibility settings and overlay app-ops, trusting a Shizuku grant as blanket consent. | Removed all automatic accessibility/overlay grants and revocations. Users must separately enable Accessibility/overlay on the phone. Check active session and Shizuku grant on shell operations. Stop touch observation on session teardown, interruption and service destruction. |
| Host trust | `ALLOW_ALL_HOSTS_VALIDATOR`. | Certificate-aware AndroidX HostValidator with Android Auto package/certificate entries from AndroidX car app 1.7.0, plus the validator's built-in trusted system/render-permission behavior. No package-name-only fallback. |
| Accessibility | All event types; broad capabilities; incorrectly claimed to be an accessibility tool. | Three focus/click event types; subscription limited to the converted package and car hosts. Removed `isAccessibilityTool`, unused gesture-generation capability and touch-exploration request capability. Password accessibility fields are not forwarded into the car keyboard. Reading relevant windows remains necessary for the optional touch bridge. |
| Display/capture | Virtual display renders the converted app onto the car surface; reflection and shell display/input commands used. | Retain own-content virtual display for navigation; no MediaProjection, camera, microphone, screen recorder, file recording or capture upload added. Removed global hidden-API exemptions. This may expose compatibility failures on newer Android versions rather than bypass platform enforcement. |
| Background work | Repeating idle cleanup loop and unsafe `Thread.stop`; possible idle foreground-service race. | Cleanup on restore/new conversion/foreground entry, no perpetual timer. Cooperative interruption, foreground-service timeout cancellation, non-sticky service, and idle-service shutdown. No boot receiver or unattended conversion start added. |
| Archives | Zip-slip checks existed, but no expansion/count budget. | Keep traversal checks; add 100,000-entry and 4 GiB expanded-byte limits, including counting actual bytes written. Very large legitimate XAPKs are intentionally rejected. |
| Backup/signing | Backup disabled, but no modern transfer exclusion rules. Package conversion already used per-package Android Keystore keys. | Explicit legacy backup disablement and cloud/device-transfer exclusions. Keep non-exportable device-local conversion keys; no fixed conversion private key embedded in Maker. Fresh local test signing identity for the Maker debug APK, distinct application ID and label. |
| Bundled runtime | Navigation runs from a prebuilt DEX asset, not directly from the edited Java source. | Rebuilt both navigation DEX copies from the hardened sources. Pinned official AndroidX car archive checksum; included a reproducible rebuild script and asset consistency/security regression tests. Other bundled DEX/JAR provenance remains a limitation. |

## Exact permission changes

These are Maker's permissions, not the original permissions belonging to an input app.

| Permission | Decision | Reason |
| --- | --- | --- |
| `android.permission.INTERNET` | Removed | Only embedded donation checkout needed it; browser handles the optional external link. |
| `android.permission.READ_EXTERNAL_STORAGE` (previously max SDK 32) | Removed | Storage Access Framework and installed APK paths suffice. |
| `android.permission.MANAGE_EXTERNAL_STORAGE` | Removed | Broad shared-storage access is not necessary for conversion. |
| `android.permission.QUERY_ALL_PACKAGES` | Removed | Only launchable apps and explicitly queried services/installers need discovery. |
| `android.permission.POST_NOTIFICATIONS` | Retained | User-visible conversion notification. Requested through Android; denial does not manufacture a grant. Android controls foreground-service visibility when notification permission is denied. |
| `android.permission.FOREGROUND_SERVICE` | Retained | User-initiated conversion can continue off screen. |
| `android.permission.FOREGROUND_SERVICE_DATA_SYNC` | Retained | Foreground local file-processing conversion on current Android versions. |
| `android.permission.REQUEST_INSTALL_PACKAGES` | Retained | User-requested installation of the converted APK, with Android unknown-source approval. |
| `moe.shizuku.manager.permission.API_V23` | Retained, dependency manifest | Optional shell installer requires explicit user choice and Shizuku authorization. Grant is not needed for ordinary conversion/save/install. |
| `<applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Retained, dependency manifest | Signature permission generated by AndroidX to protect internal dynamic receivers. Test artifact uses `com.automaker.app.adv.hardenedtest.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. |

No new Maker `uses-permission` entries were added. Existing component restrictions remain:
the Shizuku provider is exported but guarded by `android.permission.INTERACT_ACROSS_USERS_FULL`;
this is a provider access requirement, not a permission Maker requests. FileProvider,
conversion service, installer callback and secondary activities are not exported.
Dependency ProfileInstaller's exported receiver requires `android.permission.DUMP`.

For **converted navigation apps**, retain original app permissions and add the existing
navigation bridge requirements: `androidx.car.app.NAVIGATION_TEMPLATES`,
`androidx.car.app.ACCESS_SURFACE`, `android.permission.SYSTEM_ALERT_WINDOW`, and
`moe.shizuku.manager.permission.API_V23`. The injected Accessibility service is protected by
`android.permission.BIND_ACCESSIBILITY_SERVICE` (a service binding guard, not a Maker grant).
It still needs window-content access. Overlay, Accessibility and Shizuku are independent
user-controlled approvals. Maker does not sanitize every permission of third-party input apps.

## Navigation and vehicle safety

Parked-game conversion still removes distraction-optimized metadata; regression tests verify
the parked category remains. Navigation conversion now requires the original APK to declare
`maps` category, `APP_MAPS`, or a car `NAVIGATION` category. Generic games/video apps are
rejected in navigation mode. Self-declared metadata is not proof of safe behavior.

No speed/gear/motion signal spoofing, Android Auto safety-setting modification, protected
screen-capture bypass, or new motion-restriction bypass was added. The inherited navigation
architecture still mirrors an app through a virtual display and is not equivalent to a
certified native navigation integration. It has no independently verified motion safety
interlock. Do not use this test build while moving; use supported native Android Auto
navigation for driving. This remaining architectural risk is unresolved.

## Remaining Shizuku risks

- Shizuku is retained because the inherited navigation bridge uses shell operations to launch
  on a virtual display, inspect display/task state and inject mapped touch events. Removing it
  would break that bridge. Ordinary conversion and Android installation do not require it.
- Shizuku authorization grants broad shell capability to the **entire authorized app**, not
  just these helper methods. Granting it to a converted third-party APK also empowers that
  APK's existing code. Maker cannot sandbox or narrowly scope that grant. Root-started Shizuku
  can confer greater privileges; prefer an ADB/shell session for stationary testing.
- Each converted navigation app needs its own approval. A grant to Maker does not authorize
  another package. Revoke grants after testing; never grant to an untrusted package.
- Accessibility can read the permitted app/host windows and overlay can intercept touches.
  Restricting subscriptions/password forwarding reduces exposure but does not make these
  capabilities harmless or prevent the input app's own code from abusing its grants.
- Explicit Shizuku installation may copy/overwrite expansion files belonging to the selected
  package before the system installer completes. Cancelling installation does not roll back
  those file writes. Prefer the normal Android installer when sidecars are unnecessary.
- Private APIs, virtual displays, shell commands, host certificate rotation and OEM policies
  can fail on Android 17. No hidden-API exemption or permissive host fallback is restored.

## Testing and build reproduction

JDK 17, Android platform 36, build-tools 34/35, Node 24 and Gradle wrapper 8.11.1 were used.
Removed the tracked Windows `org.gradle.java.home`; honor `JAVA_HOME` instead. The conversion
integration test now discovers `ANDROID_HOME`/`ANDROID_SDK_ROOT`, tools and OS executable
suffixes, current Java, and repository root. It fails clearly when prerequisites are missing.

```sh
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
export AUTOMAKER_TEST_KEYSTORE=/private/path/security-test.keystore
# Create once, with a new identity; never use the developer release key:
keytool -genkeypair -keystore "$AUTOMAKER_TEST_KEYSTORE" -alias AndroidDebugKey \
  -storepass android -keypass android -keyalg RSA -keysize 3072 -validity 3650 \
  -dname 'CN=Android Auto Maker Security Test, O=Local Test, C=IN'
# Populate normal dependency caches with the repository's Gradle build before rebuilding DEX.
python scripts/rebuild-navigation.py
bash scripts/generate-device-fixture.sh
npm ci
npm test
cd android
bash gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  --continue :app:assembleAdvancedDebug :app:test :app:lintAdvancedDebug \
  :app:assembleAdvancedDebugAndroidTest
```

The published cloud snapshot already contains the dependencies. Its Java HTTP proxy and system
CA trust settings are required for network downloads. Rebuilding the navigation DEX retrieves
the official pinned AndroidX archive, verifies SHA-256 and uses resolved dependency caches.
Debug key passwords are conventional test defaults, not a production signing secret. Keep the
test key private; no keystore is included in the application deliverable. The instrumentation
fixture is an intentionally unsigned, code-free synthetic APK generated from committed XML.

Unit tests cover game/navigation APK conversion, rejection of non-navigation inputs,
navigation/accessibility metadata, DEX injection, re-conversion, XAPK merging, installation
bundle preparation/staging, 16 KiB alignment, cryptographic signature verification, package
renaming, traversal/resource limits and privileged sidecar destination restrictions. Packaged
permission and signing checks supplement source/asset regression tests.

Final counts, APK identity, SHA-256, certificate fingerprint, lint warnings, and copied logs/XML
are in the accompanying `validation.json` and artifact directory. Instrumentation APK compilation
is not an instrumentation test pass: no emulator/ADB/attached device was available to execute
the device test. USB/wireless Android Auto, Motorola Edge 60 Pro/Android 17 and Kia Seltos
2025 have not been validated. No production release signing/build or deployment was performed.

## Installation precautions and unresolved limitations

1. The Advanced test package is `com.automaker.app.adv.hardenedtest`, version
   `1.1.15-security-test`, with a visible Security Test label and fresh signing identity. It can
   coexist with the original Maker. It is debuggable; do not treat it as production-hardened.
2. Check the APK SHA-256 and signing certificate before any manual installation. No automated
   phone installation is part of this work. Test only while parked, with trusted source apps.
3. Converted applications are re-signed with this Maker installation's device-local Keystore
   keys. They generally cannot update the developer-signed original. Back up user data first;
   uninstalling an app or this test Maker may lose data or the keys needed for future updates.
   Optional package renaming can also break provider, login or API integrations.
4. Removal of false Google Play provenance is intentional. Android Auto or a target app may
   refuse a sideloaded/re-signed package. DRM, signature-bound map/API credentials, Play
   Integrity and OEM policy checks may fail. No bypass is supplied to make them pass.
5. Installed-app selection excludes non-launchable packages and no longer harvests shared app
   data. Use a user-selected APK/XAPK instead. OBB/data restoration remains optional and risky.
6. The shell installer uses private Android interfaces. The ordinary Android installer is the
   default fallback. Install monitoring still uses package update timestamps; real installation
   UI, revocation/rotation/crash behavior and device Keystore signing require on-device tests.
7. The navigation bridge and other bundled third-party DEX/JARs have not received a complete
   independent provenance, vulnerability or fuzzing audit. Archive limits mitigate resource
   exhaustion but are not a general hostile-parser sandbox. Cancellation is cooperative.
8. The desktop Node server was tested but is not a hardened remote service; its original
   authentication, network exposure and desktop installer behavior are outside this APK task.
   Do not expose it to untrusted networks based on this report.
