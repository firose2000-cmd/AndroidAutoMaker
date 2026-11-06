# YouTube 21.43.523 startup investigation

Status: the reported crash is confirmed in the supplied log; its conversion-related cause is not yet established. This change adds regression coverage and investigation instructions, not a verified YouTube crash fix.

## Confirmed evidence

The supplied log contains six main-thread crashes for `com.google.android.youtube.patched`, with device-reported timestamps on `11-06` from `12:12:36.587` to `12:15:17.402`. The log format does not include a year or timezone. Each identifies the patched process explicitly and fails starting `com.google.android.apps.youtube.app.watchwhile.MainActivity`:

```text
Process: com.google.android.youtube.patched
java.lang.RuntimeException: Unable to start activity
Caused by: java.lang.UnsupportedOperationException:
  Can't convert value at index 1 to color: type=0x1
at android.content.res.TypedArray.getColor(TypedArray.java:542)
at android.app.Activity.onApplyThemeResource(Activity.java:5808)
at android.view.ContextThemeWrapper.initializeTheme(ContextThemeWrapper.java:240)
```

`0x1` denotes a resource reference. The failure occurs during theme application, before the main activity finishes starting. The log names the YouTube theme hierarchy, including `Theme.YouTube.Home` at `0x7f150921`, but does not disclose the unresolved reference's data/target resource ID or the exact attribute represented by index 1 on this Motorola framework. Index 1 is a TypedArray index, not resource ID 1. The retained `com.google.android.youtube` resource-table namespace in theme diagnostics is not by itself evidence that the manifest rename failed.

The user reports matching original DEX/native hashes, the additional keyboard-provider DEX, and a larger converted resources.arsc. Those comparisons have not been independently repeated against the exact artifacts here. Table size alone cannot establish corruption: configuration consolidation, string pooling, sparse/dense entry encoding and entry padding can alter it without changing resource semantics.

The converted APK attachment exceeds the available transfer tool's 32 MiB limit. No substitute YouTube build was used. The four exact original APKs are also needed for an artifact-level comparison.

## Pipeline reviewed

Installed-app selection exports the base and split APKs to an XAPK in `InstalledApps.export`. `SessionRepository` forwards `.patched` to `PackageConverter.convertXapk`. Conversion loads the APK modules, merges base first, copies feature manifest declarations, removes split-install metadata, and repairs resource entry counts before writing `merged.apk`. `convertApk` then patches the manifest, writes the APK, adds the parked-game keyboard DEX, aligns, signs and verifies it with apksig. The parked-game path does not compile the original DEX or native libraries and does not use apktool to recompile resources. It does parse and serialize resources through ARSCLib.

`repairResourceTable` expands entry arrays and refreshes the table. The overlay-header cleanup may also rewrite resources.arsc. Both merit inspection using the exact tables, but neither has been established as the cause. Resource remapping, missing configurations, theme values and manifest-selected themes remain candidates; a resource-library upgrade or a theme override would be speculative at this stage.

The session package name comes from the in-memory manifest. Save APK streams the session's signed.apk to the chosen document. There is no final production assertion comparing that signed APK's manifest package with the reported package. The export regression tests now check this relationship.

## Regression coverage

`SplitResourceIntegrityTest` builds a four-APK synthetic fixture: base, ARM64 ABI, English and xxhdpi configuration splits. It converts both normal and sparse/name-collapsed/deduplicated tables, with package renaming both enabled and disabled. It compares:

- Original resource IDs, configuration values and symbolic theme/color references using independent Android SDK aapt2 output.
- Raw value types, numeric reference targets, style parent IDs and bag attribute IDs, including when resource names are collapsed.
- Compiled layout XML and its theme attribute references.
- SHA-256 hashes for original DEX and opaque native-library fixture payloads.
- Final APK signatures, manifest identity, SDK/ABI metadata, removal of split requirements and distinct provider authorities after renaming.
- Unchanged requested permission declarations, non-exported KeyboardStay and retained parked-game flags.

`ConverterTest.exportedApkContainsTheReportedRenamedPackage` separately reopens an export-equivalent streamed copy and checks its package with ARSCLib, aapt and apktool.

These are static tests. The synthetic native payload is an opaque byte fixture, not an executable library, and the synthetic app is not installed. Passing these tests does not establish YouTube installation, launch or playback compatibility. No production security code, permissions, grants, motion restrictions or signing policy changed.

Run from `android/` with JDK 17, SDK platform 36, build-tools 35 or newer, ANDROID_HOME or ANDROID_SDK_ROOT, and the existing test keystore/tools:

```sh
./gradlew :app:testAdvancedDebugUnitTest :app:testStandardDebugUnitTest
```

The repository's Gradle properties contain a Windows-specific JDK path; override `-Dorg.gradle.java.home=/path/to/jdk17` when necessary. The test executable paths now use the selected SDK and current JDK. From the repository root, also run `npm test` and `git diff --check`.

## Exact-artifact investigation still required

Provide the four original APKs and exact converted APK through accessible paths/links, recording SHA-256 for each. If large-file transfer is unavailable, initially extract and upload the original base/English/xxhdpi resources.arsc files and converted resources.arsc under distinct names, plus binary AndroidManifest.xml from the base and converted APK. These smaller entries permit direct table and theme comparisons without rebuilding anything.

1. Compare resource IDs, types, values and all configurations across the union of original APKs and the converted table. Inspect the logged theme hierarchy and follow color-reference chains. Check parent/attribute IDs and framework references against the actual Motorola Android build.
2. Compare before merge, after merge/entry-count repair, after manifest patching and after signing. Identify the first stage where the affected reference/configuration changes. Record rather than infer changes from byte lengths.
3. Use controlled conversions with and without `.patched` and with the same complete split set. Preserve original code/native hashes, and compare manifest-selected themes and authorities. Do not use missing splits as a substitute for the actual input.
4. Implement a correction only after a failing artifact-backed regression demonstrates the defect. Preserve original resource IDs where original executable code references them. Re-run signature, alignment, identity, permission and payload checks.

For a new timestamped device log, with the patched app already installed, capture only a fresh launch window. These commands launch the existing patched app; they do not install or grant permissions:

```sh
adb shell date
adb shell getprop ro.build.fingerprint
adb shell am force-stop com.google.android.youtube.patched
adb logcat -b main -b system -b crash -v threadtime -T 1 > youtube-fresh-logcat.txt
```

While logcat runs, tap the patched YouTube icon, then stop capture after the failure. Retain the matching `Process:` line and entire exception chain. Logcat may contain unrelated private information; share the relevant launch window rather than a full device-history dump.

## Generating and validating a candidate after a justified fix

No signed test release is warranted yet: this patch changes tests and documentation only and cannot improve YouTube startup. A future candidate should retain the established release signing configuration and use a distinct test build identifier. Do not distribute signing keys or replace them with the fixture keystore.

For that future candidate, select the installed original YouTube 21.43.523, ensure all four original splits are collected, choose **Parked game**, enable **Add .patched to the package name**, convert and save to a new destination. Before installation, verify the saved manifest says `com.google.android.youtube.patched`, confirm distinct provider authorities, verify the signature/alignment and compare original DEX/native hashes. Record the candidate Maker build ID and output SHA-256.

Validation remains pending until performed on the phone and vehicle:

- Both original and patched YouTube remain installed under distinct package names; the original is unchanged.
- Patched YouTube cold-launches repeatedly in light/dark themes without the logged color exception.
- Core playback, seeking, audio, rotation and background/foreground transitions work on the phone; retain a timestamped stability log.
- Requested permissions and security restrictions match the hardened baseline. No Accessibility/overlay/Shizuku grant is added or performed automatically.
- Separately, on the actual Android Auto head unit while safely parked, check launcher visibility and parked playback. Confirm the existing motion gate remains effective through a safe, controlled validation; do not enable video while driving or bypass the gate.

Until those device checks pass, the acceptance criteria are unmet and the YouTube startup issue remains open.
