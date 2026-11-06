# YouTube 21.43.523 sparse-resource startup failure

Status: resource-index corruption is reproduced from the supplied tables and corrected in both merge implementations. Automated tests pass; YouTube launch, playback and parked-only Android Auto behavior still require device validation. Keep the PR in draft until that validation is complete.

## Root cause and affected references

The fresh supplied log identifies six main-thread crashes in `com.google.android.youtube.patched` on device-reported `11-06`, from `12:12:36.587` to `12:15:17.402`, starting `com.google.android.apps.youtube.app.watchwhile.MainActivity`. It fails in `TypedArray.getColor`, called by `Activity.onApplyThemeResource`, with an unresolved resource reference (`type=0x1`). The log has no timezone/year field.

The supplied binary manifest independently confirms the patched package, main activity theme `0x7f150921` (`Theme.YouTube.Home`, matching the crash log), and absence of `android:debuggable`.

The defect is in serialized sparse resource indexes, not the manifest package or a changed color literal. A sparse type stores `(entry ID, offset)` pairs for populated IDs only. Android binary-searches those pairs, requiring strictly ascending IDs. The resource library can enumerate the same malformed table using linear lookup, concealing the failure from semantic-only comparisons.

| Supplied resource table | Sparse chunks | Unsorted/duplicate indexes |
| --- | ---: | ---: |
| Original base | 128 | 0 |
| Original English | 2 | 0 |
| Original xxhdpi | 6 | 0 |
| Converted | 135 | 135 |

The original tables' union has 35,377 resource ID/configuration records. The old converted table retains their decoded names/types/values, but also has 133 phantom ID-zero/configuration records. Its indexes are invalid even though a linear semantic comparison appears reassuring.

A specific affected light-theme chain is:

```text
Theme.YouTube.Home (0x7f150921)
  ... inherited android:colorPrimary (0x01010435)
  -> ?0x7f0401fb
  -> ?0x7f040b68
  -> @color/yt_dark_blue (0x7f060dfd)
  -> @color/yt_ref_color_constants_baseline_blue_dark_blue (0x7f060e9c)
  -> #ff065fd4
```

Both color IDs resolve by binary search in the original default color index (2,505 populated pairs). Both are present but cannot be found by binary search in the supplied converted index (incorrectly padded to 4,454 pairs, with descending IDs followed by duplicate zeros). This demonstrates a broken theme reference chain. The log does not separately disclose the reference data or Motorola's precise internal mapping for TypedArray index 1; the final runtime correlation remains part of device validation.

Two operations explain the defect:

1. ARSCLib 1.3.8 sparse merge insertion can produce descending entry-ID indexes.
2. `repairResourceTable` applies dense-ID-space padding to sparse lists. Its `ensureEntriesCount(needed)` creates duplicate ID-zero sparse records instead of dense holes.

Table byte growth alone was insufficient evidence; the supplied tables establish the malformed layout directly. No theme override or original executable-code change is needed for this correction.

## Correction and changed files

Both implementations now leave sparse lists compact and explicitly reorder entry objects together with their offset/ID objects by ascending ID. Dense-table count repair remains in place:

- `android/app/src/main/java/com/automaker/app/engine/PackageConverter.kt`
- `tools/src/com/automaker/merge/MergeXapk.java`

The change affects resource serialization only. It does not alter permissions, signing policy, provider exposure, Accessibility/overlay/Shizuku grants or vehicle-motion restrictions. The original YouTube installation was not accessed or modified. Uploaded diagnostics were read; analysis copies and generated shells are separate scratch files.

`SplitResourceIntegrityTest.kt` adds binary-layout checks, genuinely sparse fixtures and an optional exact-table integration test. `ConverterTest.kt` contains the preceding exported-package regression and portable SDK/JDK discovery. This document records the evidence and validation procedure.

## Regression evidence

The synthetic tests now force sparse encoding, assert that sparse chunks actually exist, and independently check serialized ID ordering and entry bounds. Multiple gapped night-mode entries cover both padding and reordering. On old production code the regression failed with `previous=2 id=0` in the signed output. It passes with the correction.

Existing checks cover resource IDs/configurations, numeric reference targets, style parents, bag attribute IDs, compiled XML, original fixture DEX/native hashes, package/authority renaming, signatures, SDK/ABI metadata, permissions and parked-game settings. The preceding export test checks the saved-copy package using ARSCLib, aapt and apktool.

The optional `suppliedYouTubeTablesRemainSearchableAfterConversion` test reads the five diagnostics from `YOUTUBE_RESOURCE_TABLES`. It confirms that the supplied converted table fails the binary-index check, constructs diagnostic APK shells from the exact original tables and supplied manifest, and runs both the real Android conversion/signing path and desktop merge CLI. These shells lack original executable code and resource files: never install them.

Both corrected paths preserve all 35,377 resource ID/configuration/type/value records, with no extra or missing records, and produce 135 valid consolidated sparse indexes. Android output signature verification passes. Both diagnostic paths produce a 2,432,740-byte resource table. This is not a regenerated complete YouTube APK.

Validation completed with supplied diagnostics enabled:

- `:app:testAdvancedDebugUnitTest`: 11 passed, zero failures/errors/skips.
- `:app:testStandardDebugUnitTest`: 11 passed, zero failures/errors/skips.
- Desktop merge source compilation and exact-table checks: passed in the integration test.
- `:app:compileAdvancedReleaseKotlin`: passed.
- `npm test` and `git diff --check`: passed.

No application was installed or launched; no merge or release publication occurred. A release APK was not signed because the established `android/release.keystore` is absent from this workspace. Release compilation is not a signed build or evidence of device acceptance.

## Re-running tests

Use JDK 17, SDK platform 36 and Android build-tools 35 or newer. Set ANDROID_HOME or ANDROID_SDK_ROOT. Existing tests require apktool, fixture DEX assets and the repository's test-only signing.p12. The optional test additionally compiles desktop source with the current JDK.

Place the five diagnostic files in a private folder under their supplied filenames. From `android/`:

```sh
export YOUTUBE_RESOURCE_TABLES=/absolute/path/to/diagnostic-files
./gradlew -Dorg.gradle.java.home=/absolute/path/to/jdk17 \
  :app:testAdvancedDebugUnitTest :app:testStandardDebugUnitTest \
  :app:compileAdvancedReleaseKotlin
```

Without the environment variable, the optional exact-table test skips; synthetic regressions still run. Do not commit extracted YouTube tables or private device logs.

## Next build and device validation

1. Check out PR branch `codex/youtube-startup-resource-regressions-20261106`.
2. Supply the established Maker release keystore locally at `android/release.keystore` through the existing signing configuration. Do not upload/commit it or substitute the fixture key. Use a distinct test build identifier before distribution.
3. From `android/`, run:

   ```sh
   ./gradlew -Dorg.gradle.java.home=/absolute/path/to/jdk17 :app:assembleAdvancedRelease
   ```

   The configured output is under `android/app/build/outputs/apk/advanced/release/`. Verify its signature against the established Maker certificate before use. No signing identity was generated or changed here.

4. Using that candidate, select the installed original YouTube 21.43.523 and collect all four original APKs: base, ARM64, English and xxhdpi. Select **Parked game** and **Add .patched to the package name**. Convert again from the originals and save to a new destination. Do not reconvert the already-corrupted converted APK: sorting cannot reliably recover ambiguous duplicate-ID records.
5. Verify saved package identity, signature/alignment, original eight DEX and 37 ARM64 library hashes, and unchanged permissions/security settings. Record Maker build ID and output SHA-256.

Acceptance remains pending until independently checked:

- Patched APK installs alongside the unchanged original.
- Repeated cold starts in light/dark themes succeed without the color exception.
- Phone playback, seeking, audio, rotation and background/foreground transitions work; retain a fresh timestamped stability log.
- No sensitive permission or automatic Accessibility/overlay/Shizuku authorization is added.
- Separately on the actual Android Auto head unit, check launcher visibility and playback while safely parked. Validate the existing motion gate through a safe controlled check; do not bypass it or enable video while driving.

## Diagnostic provenance

SHA-256 values for supplied files; the files themselves are not committed:

```text
Converted_AndroidManifest.xml
e92e7473d01ae563d8cf4d28fb70da1e571503ea43674841ac8e875eb66f69d7
Original_Base_resources.arsc
ae540f31533444eb96153ec485d94462a23d90db2a5c87511ca2c132c96f09d8
Original_English_resources.arsc
ece1e9eae8641cea6e2af0254ca75d6eadbd2e87fb180e841d98e141c3f38f4c
Original_XXHDPI_resources.arsc
592f76c93f35ad106a1524caeb1ec2c8a79c838e3c65ce0a6ee725fe3ef76da1
Converted_resources.arsc
e69115c12f18211ccc3a296f4987da6c404b0ca349327195820f356d8ccedda8
```
