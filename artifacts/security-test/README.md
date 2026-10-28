# Download the existing hardened Advanced test APK

**Historical debug build:** the default download is now the separately signed,
[non-debuggable test release](../test-release/README.md). This directory preserves the old APK
and its original checksum. The instructions below describe the earlier debug-artifact flow;
select legacy `existing` mode only if you deliberately want that older debuggable build.

This directory contains the **original signed APK** built from application commit
`39078d9e5440c6ee6fb69069100ddaeec6b10f42`. It was copied without rebuilding or changing its bytes.
It is deliberately stored on `security/hardened-advanced-1.1.15` because the cloud chat's
download exporter repeatedly failed. The binary is approximately 11.2 MB, below GitHub's
normal Git file limit; no external file host, release, Git LFS service or secret is needed.

APK SHA-256:

```text
503f3a3f6c581a871db5f032f561a8881fe8a70b638bba459993d4dc29311705
```

Signing certificate SHA-256:

```text
8f19dd6ed340086cf4d701841dab913170a7add143f15c0987556e285cb5bf4d
```

## Download in GitHub

1. Open `firose2000-cmd/AndroidAutoMaker` in GitHub and select branch
   **security/hardened-advanced-1.1.15**.
2. Select **Actions**, then **Download hardened Advanced test APK**. The first push of this
   workflow starts an **existing**-APK packaging run. It does not build the application.
3. Open that run. If it is already completed and you need another copy, choose
   **Re-run all jobs**. This also packages the same existing APK.
4. Wait for the green check. On the run's **Summary** page, scroll to **Artifacts** and click
   **hardened-advanced-1.1.15-existing-<run-number>** (the numeric suffix is GitHub's run ID).
5. Extract the downloaded ZIP. It contains the signed APK, `SHA256SUMS`, `build-info.json`
   and `signature-verification.txt`. The run summary also shows the actual APK checksum and
   certificate fingerprint. You must be signed in with repository access to download artifacts.
   Artifacts expire after 14 days; re-run the job to create a new artifact of the same APK.

You can alternatively open the APK in this directory on GitHub and click **Download raw file**.
That retrieves the same checked-in bytes without any Actions run.

## Start another run manually without changing master

The workflow provides a **Run workflow** form with `existing` (default) and `rebuild` choices.
If GitHub shows it, select the security branch and leave the mode at **existing**.

GitHub generally requires a `workflow_dispatch` workflow to exist on the repository's default
branch before displaying/accepting manual dispatch. This workflow intentionally does not add
anything to master. If **Run workflow** is unavailable, use the branch-only browser fallback:

1. Select **security/hardened-advanced-1.1.15** in the repository's **Code** tab.
2. Open `.github/hardened-artifact-request.json`, then click the pencil to edit it.
3. Leave `"mode": "existing"` and change `"request": "download-1"` to `"request": "download-2"`
   (or any new request text).
4. Click **Commit changes** and explicitly choose **Commit directly to the
   security/hardened-advanced-1.1.15 branch**. Do not select master or create/merge a pull request.
5. Return to **Actions** and download the new run's artifact as above. If Actions are disabled
   for the repository, enable them in GitHub before making the request commit.

## Rebuild only if necessary

The existing APK is committed here, so rebuilding is unnecessary for the original download.
No missing/corrupt APK condition silently triggers a build: existing mode fails verification.

Only if you explicitly need a replacement build, choose **rebuild** in the manual-run form, or
set `"mode": "rebuild"` in the request file and commit on the security branch. The conditional
path installs SDK prerequisites, creates a fresh runner-local RSA test key, builds
**Advanced debug**, runs its unit tests and lint, verifies the signed APK, and uploads it with
its **newly measured checksum and signing certificate fingerprint**. It does not use or upload
the original test private key, a developer release key, or any production signing secret.

A rebuilt APK will normally differ from the original because the signing identity is fresh.
The workflow reports the actual comparison rather than assuming either equality or inequality.
`build-info.json` distinguishes the original application commit from the current checkout commit
and records whether rebuilding occurred. Read the run summary for the new checksum/fingerprint;
the original checksum above must never be used to identify a rebuilt file without verification.

For subsequent existing-APK downloads using the edit-file trigger, change the mode back to
**existing**. Re-running an old run repeats that old run's mode and checkout, including a rebuild
if that was originally requested. No releases, installs or deployments are performed in either mode.

## Verify the extracted APK on your computer

PowerShell:

```powershell
Get-FileHash .\AndroidAutoMaker-Advanced-1.1.15-hardened-existing.apk -Algorithm SHA256
```

Linux:

```sh
sha256sum -c SHA256SUMS
```

macOS:

```sh
shasum -a 256 AndroidAutoMaker-Advanced-1.1.15-hardened-existing.apk
```

Compare the **APK's** checksum, not the outer GitHub-generated ZIP's checksum. This remains a
debuggable stationary test build; Android 17, USB/wireless Android Auto and vehicle compatibility
are unverified. See [the security audit](../../SECURITY_AUDIT.md) for remaining Shizuku and motion-safety risks.
