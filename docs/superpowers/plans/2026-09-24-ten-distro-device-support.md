# Ten-Distro APK Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the APK select, download, boot, and test all ten published arm64 rootfs distributions, including QEMU and AVF paths.

**Architecture:** Extend the persisted `Distro` enum as the single source of truth for fixed assets, preferred versioned assets, and package-index commands. `SystemImageRepository` supplies the selected rootfs to both engines, while a small per-distro DataStore state and a dynamic asset extractor keep downloads, bundled fallbacks, and first-boot behavior isolated. CI verifies the ten release assets and runs JVM tests before assembling the APK.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, DataStore, JUnit 4, Gradle 9.3.1, Android SDK platform 36, Bash, GitHub Actions, adb/QEMU.

**Spec:** `docs/superpowers/specs/2026-09-24-ten-distro-device-support-design.md`

## Global Constraints

- Preserve all existing `Distro` enum names because they are persisted DataStore values.
- Do not add a runtime dependency for distro metadata or package commands.
- Keep QEMU and AVF rootfs selection behind `SystemImageRepository`.
- A package-index refresh is best-effort and must never gate `Ready!` or `Running`.
- The ten fixed release assets are the runtime download contract.
- The app remains APK-only; sibling repositories provide kernel, initramfs, rootfs, and QEMU artifacts.
- QEMU must be tested on a real arm64 Android device. AVF is tested only when the device reports virtualization support and grants succeed.
- `adb devices` was empty at plan time, so hardware validation is a later gated step.
- Do not add inline code comments. Documentation changes must keep English and Chinese resources synchronized where user-facing strings are affected.

## Review Focus

- A non-Kali selection must never resolve to `kali-rootfs.squashfs` in either engine.
- A custom rootfs URL must keep its existing persistence behavior while the selected asset name remains the local filename.
- The first package update must be isolated per distro and must send the complete command bytes.
- A bundled asset must never overwrite a complete user-downloaded file.
- A missing optional rootfs asset must not crash application startup, while VM launch must fail clearly.
- All ten fixed release URLs must exist before claiming runtime support.

---

### Task 1: Extend the distro descriptor and pure mappings

**Files:**
- Modify: `app/src/main/java/com/excp/podroid/data/repository/SystemImageRepository.kt:26-82`
- Modify: `app/src/test/java/com/excp/podroid/data/repository/DistroTest.kt:7-57`

**Interfaces:**
- Produces `Distro.asset: String`.
- Produces `Distro.versionedAsset: String`.
- Produces `Distro.packageUpdateCommand: String`.
- Produces `SystemImageRepository.versionedPresetUrl(distro: Distro): String`.

- [ ] **Step 1: Write failing mapping tests**

Add an exhaustive expected map for all ten values, including versioned assets and commands:

```kotlin
private val expected = mapOf(
    Distro.KALI to Triple("kali-rootfs.squashfs", "kali-rootfs-rolling.squashfs", "apt-get update"),
    Distro.DEBIAN to Triple("debian-rootfs.squashfs", "debian-rootfs-12.squashfs", "apt-get update"),
    Distro.UBUNTU to Triple("ubuntu-rootfs.squashfs", "ubuntu-rootfs-24.04.squashfs", "apt-get update"),
    Distro.FEDORA to Triple("fedora-rootfs.squashfs", "fedora-rootfs-42.squashfs", "dnf makecache --refresh"),
    Distro.ROCKY to Triple("rocky-rootfs.squashfs", "rocky-rootfs-9.squashfs", "dnf makecache --refresh"),
    Distro.ALMA to Triple("alma-rootfs.squashfs", "alma-rootfs-9.squashfs", "dnf makecache --refresh"),
    Distro.OPENSUSE to Triple("opensuse-rootfs.squashfs", "opensuse-rootfs-15.6.squashfs", "zypper --non-interactive refresh"),
    Distro.ARCH to Triple("arch-rootfs.squashfs", "arch-rootfs-rolling.squashfs", "pacman -Sy --noconfirm"),
    Distro.MANJARO to Triple("manjaro-rootfs.squashfs", "manjaro-rootfs-rolling.squashfs", "pacman -Sy --noconfirm"),
    Distro.GENTOO to Triple("gentoo-rootfs.squashfs", "gentoo-rootfs-rolling.squashfs", "emerge --sync --quiet"),
)

@Test
fun everyDistroHasStableVersionedAssetAndUpdateCommand() {
    assertEquals(expected, Distro.values().associateWith { Triple(it.asset, it.versionedAsset, it.packageUpdateCommand) })
}

@Test
fun versionedPresetUrlUsesTheSameReleaseBase() {
    for (distro in Distro.values()) {
        assertEquals(
            "${SystemImageRepository.ROOTFS_BASE_URL}/${distro.versionedAsset}",
            SystemImageRepository.versionedPresetUrl(distro),
        )
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.excp.podroid.data.repository.DistroTest
```

Expected: compilation or assertion failure because the new enum properties and URL helper do not exist.

- [ ] **Step 3: Implement the minimal descriptor**

Change the enum to:

```kotlin
enum class Distro(
    val asset: String,
    val versionedAsset: String,
    val packageUpdateCommand: String,
) {
    KALI("kali-rootfs.squashfs", "kali-rootfs-rolling.squashfs", "apt-get update"),
    DEBIAN("debian-rootfs.squashfs", "debian-rootfs-12.squashfs", "apt-get update"),
    UBUNTU("ubuntu-rootfs.squashfs", "ubuntu-rootfs-24.04.squashfs", "apt-get update"),
    FEDORA("fedora-rootfs.squashfs", "fedora-rootfs-42.squashfs", "dnf makecache --refresh"),
    ROCKY("rocky-rootfs.squashfs", "rocky-rootfs-9.squashfs", "dnf makecache --refresh"),
    ALMA("alma-rootfs.squashfs", "alma-rootfs-9.squashfs", "dnf makecache --refresh"),
    OPENSUSE("opensuse-rootfs.squashfs", "opensuse-rootfs-15.6.squashfs", "zypper --non-interactive refresh"),
    ARCH("arch-rootfs.squashfs", "arch-rootfs-rolling.squashfs", "pacman -Sy --noconfirm"),
    MANJARO("manjaro-rootfs.squashfs", "manjaro-rootfs-rolling.squashfs", "pacman -Sy --noconfirm"),
    GENTOO("gentoo-rootfs.squashfs", "gentoo-rootfs-rolling.squashfs", "emerge --sync --quiet"),
}
```

Add beside `presetUrl`:

```kotlin
fun versionedPresetUrl(distro: Distro): String = "$ROOTFS_BASE_URL/${distro.versionedAsset}"
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same Gradle command. Expected: all `DistroTest` tests pass.

- [ ] **Step 5: Commit the descriptor slice**

```bash
git add app/src/main/java/com/excp/podroid/data/repository/SystemImageRepository.kt app/src/test/java/com/excp/podroid/data/repository/DistroTest.kt
git commit -m "feat(data): define ten distro asset metadata"
```

### Task 2: Isolate first package-index state per distro

**Files:**
- Modify: `app/src/main/java/com/excp/podroid/data/repository/SettingsRepository.kt:94-95,232-234`
- Modify: `app/src/main/java/com/excp/podroid/data/repository/SystemImageRepository.kt:62-64,105-110`
- Modify: `app/src/test/java/com/excp/podroid/data/repository/DistroTest.kt`

**Interfaces:**
- Produces `SettingsRepository.isFirstPackageUpdateDone(distro: Distro): Boolean`.
- Produces `SettingsRepository.markFirstPackageUpdateDone(distro: Distro)`.
- Produces `SettingsRepository.clearFirstPackageUpdateDone(distro: Distro)`.
- Retains the on-disk literal `first_apt_update_done` for Kali compatibility.

- [ ] **Step 1: Add failing pure state-key tests**

Add tests for a package-update preference key helper and legacy fallback:

```kotlin
@Test
fun packageUpdateStateIsPerDistro() {
    assertEquals("first_package_update_done_manjaro", firstPackageUpdateKeyName(Distro.MANJARO))
    assertEquals("first_package_update_done_gentoo", firstPackageUpdateKeyName(Distro.GENTOO))
}

@Test
fun legacyAptKeyOnlyMigratesKali() {
    assertTrue(legacyFirstUpdateApplies(Distro.KALI))
    assertFalse(legacyFirstUpdateApplies(Distro.FEDORA))
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.excp.podroid.data.repository.DistroTest
```

Expected: compilation failure because the key helpers do not exist.

- [ ] **Step 3: Implement per-distro state and migration fallback**

Add package-visible helpers in `SettingsRepository.kt`:

```kotlin
internal fun firstPackageUpdateKeyName(distro: Distro): String =
    "first_package_update_done_${distro.name.lowercase()}"

internal fun legacyFirstUpdateApplies(distro: Distro): Boolean = distro == Distro.KALI
```

Add a private key factory and replace the old apt-only accessors with:

```kotlin
private fun firstPackageUpdateKey(distro: Distro): Preferences.Key<Boolean> =
    booleanPreferencesKey(firstPackageUpdateKeyName(distro))

suspend fun isFirstPackageUpdateDone(distro: Distro): Boolean {
    val values = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .first()
    return values[firstPackageUpdateKey(distro)]
        ?: (legacyFirstUpdateApplies(distro) && values[KEY_FIRST_APT_UPDATE_DONE] == true)
}

suspend fun markFirstPackageUpdateDone(distro: Distro) {
    context.dataStore.edit { it[firstPackageUpdateKey(distro)] = true }
}

suspend fun clearFirstPackageUpdateDone(distro: Distro) {
    context.dataStore.edit {
        it.remove(firstPackageUpdateKey(distro))
        if (legacyFirstUpdateApplies(distro)) it.remove(KEY_FIRST_APT_UPDATE_DONE)
    }
}
```

Keep `KEY_FIRST_APT_UPDATE_DONE` declared with its original string literal. Inject `SettingsRepository` into `SystemImageRepository` and update `setDistro` so it clears the new distro's completion key only when the selected distro changes:

```kotlin
val previous = distro()
context.dataStore.edit {
    it[KEY_DISTRO] = distro.name
    it[KEY_ROOTFS_URL] = presetUrl(distro)
    it[KEY_DOWNLOADED] = false
}
if (previous != distro) settingsRepository.clearFirstPackageUpdateDone(distro)
```

This keeps a fresh selection from inheriting another distro's update state while preserving the old Kali fallback.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the same Gradle command. Expected: all `DistroTest` tests pass.

- [ ] **Step 5: Commit the state slice**

```bash
git add app/src/main/java/com/excp/podroid/data/repository/SettingsRepository.kt app/src/main/java/com/excp/podroid/data/repository/SystemImageRepository.kt app/src/test/java/com/excp/podroid/data/repository/DistroTest.kt
git commit -m "fix(data): isolate first update state by distro"
```

### Task 3: Make QEMU and AVF consume the selected rootfs and command

**Files:**
- Modify: `app/src/main/java/com/excp/podroid/engine/QemuEngine.kt:51-55,167-179,597-615,681-687`
- Modify: `app/src/main/java/com/excp/podroid/engine/avf/AvfEngine.kt:55-58,1019-1029`
- Modify: `app/src/test/java/com/excp/podroid/data/repository/DistroTest.kt`

**Interfaces:**
- Consumes `SystemImageRepository.rootfsFile()` in both engines.
- Consumes `Distro.packageUpdateCommand` and `SettingsRepository.isFirstPackageUpdateDone(distro)`.
- Produces a complete-byte terminal write with `bytes.size` as the length.

- [ ] **Step 1: Add failing tests for the package command and no Kali hard-code**

Extend `DistroTest` with the command matrix assertion. Add this exact source regression assertion to the shell verification for this task:

```bash
if grep -Fq 'File(context.filesDir, "kali-rootfs.squashfs")' app/src/main/java/com/excp/podroid/engine/avf/AvfEngine.kt; then
    exit 1
fi
```

- [ ] **Step 2: Run the focused test and verify RED**

Run the Gradle test and the source assertion:

```bash
./gradlew :app:testDebugUnitTest --tests com.excp.podroid.data.repository.DistroTest
if grep -Fq 'File(context.filesDir, "kali-rootfs.squashfs")' app/src/main/java/com/excp/podroid/engine/avf/AvfEngine.kt; then
    exit 1
fi
```

Expected: the source assertion fails while the AVF hard-code remains.

- [ ] **Step 3: Update QEMU first-boot behavior**

In `newBootStageDetector`, read the selected distro and call:

```kotlin
val distro = systemImageRepository.distro()
if (!settingsRepository.isFirstPackageUpdateDone(distro)) {
    runFirstPackageUpdate(distro)
}
```

Replace `runFirstAptUpdate` with:

```kotlin
private suspend fun runFirstPackageUpdate(distro: Distro) {
    val session = _terminalSession ?: return
    try {
        val bytes = "${distro.packageUpdateCommand}\n".toByteArray()
        session.write(bytes, 0, bytes.size)
        kotlinx.coroutines.delay(15_000)
        settingsRepository.markFirstPackageUpdateDone(distro)
    } catch (e: Exception) {
        Log.w(TAG, "First package update failed for ${distro.name}", e)
    }
}
```

Keep the operation best-effort and preserve the `Ready!` state transition.

- [ ] **Step 4: Inject the repository into AVF and remove the hard-code**

Add `SystemImageRepository` to the `AvfEngine` constructor. Replace the hard-coded rootfs block with:

```kotlin
val squashfs = systemImageRepository.rootfsFile().also {
    require(it.exists()) { "rootfs missing at ${it.absolutePath}" }
}
```

Do not change AVF VM naming, kernel handling, initramfs, or console wiring.

- [ ] **Step 5: Run tests and verify GREEN**

Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.excp.podroid.data.repository.DistroTest --tests com.excp.podroid.engine.QemuLaunchRulesTest
```

Expected: all selected tests pass and the source regression check finds no AVF Kali literal.

- [ ] **Step 6: Commit the engine slice**

```bash
git add app/src/main/java/com/excp/podroid/engine/QemuEngine.kt app/src/main/java/com/excp/podroid/engine/avf/AvfEngine.kt app/src/test/java/com/excp/podroid/data/repository/DistroTest.kt
 git commit -m "fix(engine): boot the selected distro on qemu and avf"
```

### Task 4: Support all ten optional bundled rootfs assets

**Files:**
- Modify: `app/src/main/java/com/excp/podroid/PodroidApplication.kt:104-129`
- Modify: `.gitignore:25-30`
- Modify: `app/src/test/java/com/excp/podroid/data/repository/DistroTest.kt`

**Interfaces:**
- Consumes `Distro.values().map { it.asset }`.
- Produces optional bundled extraction for every available rootfs while preserving complete downloaded files.

- [ ] **Step 1: Add a failing asset-set regression check**

Run this exact shell assertion before editing:

```bash
for asset in kali-rootfs.squashfs debian-rootfs.squashfs ubuntu-rootfs.squashfs \
             fedora-rootfs.squashfs rocky-rootfs.squashfs alma-rootfs.squashfs \
             opensuse-rootfs.squashfs arch-rootfs.squashfs manjaro-rootfs.squashfs \
             gentoo-rootfs.squashfs; do
    grep -Fxq "app/src/main/assets/$asset" .gitignore
 done
```

Expected: FAIL because `.gitignore` currently lists only the Kali filename.

- [ ] **Step 2: Run the focused test and verify RED**

Run the shell assertion again and then run the existing exhaustive asset mapping test:

```bash
./gradlew :app:testDebugUnitTest --tests com.excp.podroid.data.repository.DistroTest
```

Expected: the shell assertion fails before the ignore-rule edit; the existing mapping test remains green.

- [ ] **Step 3: Implement dynamic extraction**

Import `Distro`. Build a downloaded-file map for all ten assets. Build bundled rootfs tasks from `assets.list("")` so missing optional assets are skipped rather than logged as failures:

```kotlin
val rootfsAssets = Distro.values().map { it.asset }.toSet()
val bundledRootfsAssets = rootfsAssets.filter { assets.list("")?.contains(it) == true }
val downloadedRootfs = rootfsAssets.associateWith { isDownloadedFile(File(filesDir, it)) }
```

Add one extraction task per bundled rootfs, using its own filename as the destination. Keep the kernel, initramfs, and QEMU tasks unchanged. A complete downloaded file must always win over a bundled file.

- [ ] **Step 4: Update ignore rules and run tests**

Add all ten fixed rootfs filenames below the existing generated-artifact rules. Run:

```bash
./gradlew :app:testDebugUnitTest --tests com.excp.podroid.data.repository.DistroTest
```

Expected: PASS.

- [ ] **Step 5: Commit the asset slice**

```bash
git add app/src/main/java/com/excp/podroid/PodroidApplication.kt .gitignore app/src/test/java/com/excp/podroid/data/repository/DistroTest.kt
git commit -m "feat(assets): handle all bundled rootfs selections"
```

### Task 5: Replace the gh-only fetch script and add release checks

**Files:**
- Modify: `fetch-artifacts.sh:1-70`
- Create: `scripts/verify-rootfs-assets.sh`

**Interfaces:**
- `fetch-artifacts.sh <canonical-distro>` accepts `kali`, `debian`, `ubuntu`, `fedora`, `rocky`, `alma`, `opensuse`, `arch`, `manjaro`, or `gentoo`.
- The selected rootfs is stored under its fixed asset name, not renamed to Kali.
- `scripts/verify-rootfs-assets.sh` returns nonzero if any fixed release URL is unavailable.

- [ ] **Step 1: Write failing shell checks**

Create a small temporary shell assertion that the script contains all ten cases, does not invoke `gh release download`, and preserves the destination name. Run it before editing and verify it fails.

Example assertions:

```bash
for key in kali debian ubuntu fedora rocky alma opensuse arch manjaro gentoo; do
    grep -q "${key})" fetch-artifacts.sh
 done
! grep -q 'gh release download' fetch-artifacts.sh
```

- [ ] **Step 2: Run `bash -n` and verify RED**

Run:

```bash
bash -n fetch-artifacts.sh
```

Expected: syntax passes, while the behavior assertions fail because the current script has only three cases and uses `gh`.

- [ ] **Step 3: Implement direct release downloads**

Use a case statement for fixed and versioned names, a `download_asset` helper using `curl -fL --retry 3`, and optional `GITHUB_TOKEN` as an Authorization header. Download the versioned rootfs first, fall back to the fixed rootfs, and copy the result to `app/src/main/assets/<fixed-name>`. Keep the existing QEMU archive extraction and common artifact downloads. Reject unknown keys with a nonzero exit.

- [ ] **Step 4: Implement the CI asset checker**

Create a Bash script that loops over the ten fixed URLs and runs a headers-only request:

```bash
for asset in kali-rootfs.squashfs debian-rootfs.squashfs ubuntu-rootfs.squashfs \
             fedora-rootfs.squashfs rocky-rootfs.squashfs alma-rootfs.squashfs \
             opensuse-rootfs.squashfs arch-rootfs.squashfs manjaro-rootfs.squashfs \
             gentoo-rootfs.squashfs; do
    curl -fsSIL --retry 3 -o /dev/null \
        "https://github.com/nike64542-byte/poroid-rootfs/releases/download/latest/$asset"
done
```

Add `GITHUB_TOKEN` as an optional header without printing it.

- [ ] **Step 5: Run shell checks and verify GREEN**

Run:

```bash
bash -n fetch-artifacts.sh scripts/verify-rootfs-assets.sh
```

Expected: PASS. Run the checker once against the public release and record the ten successful responses.

- [ ] **Step 6: Commit the artifact slice**

```bash
git add fetch-artifacts.sh scripts/verify-rootfs-assets.sh
git commit -m "build: support all rootfs release artifacts"
```

### Task 6: Make CI test and build with the declared Android SDK

**Files:**
- Modify: `.github/workflows/build.yml:43-50,70-84`

**Interfaces:**
- CI runs `scripts/verify-rootfs-assets.sh` before Gradle.
- CI runs `:app:testDebugUnitTest` before assembling the selected APK variant.

- [ ] **Step 1: Write a workflow regression check**

Add a temporary shell assertion that the workflow contains `platforms;android-36`, `:app:testDebugUnitTest`, and does not contain `sdkmanager ... || true`. Verify it fails against the current workflow.

- [ ] **Step 2: Run the assertion and verify RED**

Run the assertion directly and confirm the old SDK and missing test step are detected.

- [ ] **Step 3: Implement CI changes**

Install:

```bash
sdkmanager "platform-tools" "platforms;android-36" "build-tools;35.0.0"
```

Remove the `|| true` from SDK package installation. Add an asset verification step and a unit-test step before the existing build step. Preserve the existing signing branch, version properties, artifact upload, and GPG signature steps.

- [ ] **Step 4: Validate workflow syntax and verify GREEN**

Run:

```bash
python3 - <<'PY'
from pathlib import Path
import yaml
yaml.safe_load(Path('.github/workflows/build.yml').read_text())
print('workflow YAML parsed')
PY
```

Then run the workflow regression assertion. Expected: both pass.

- [ ] **Step 5: Commit the CI slice**

```bash
git add .github/workflows/build.yml
git commit -m "ci: test and verify apk release inputs"
```

### Task 7: Synchronize user and contributor documentation

**Files:**
- Modify: `README.md:3-34`
- Modify: `CLAUDE.md:5-58,168-182,197-209,239-268`
- Modify: `CONTRIBUTING.md:9-18,21-38,77-99`
- Modify: `docs/guide/getting-started.html:67-95`
- Modify: `docs/guide/packages.html:51-55`

**Interfaces:**
- Documentation must name all ten fixed rootfs assets and the runtime download flow.
- Device instructions must distinguish QEMU on any arm64 phone from AVF capability-gated validation.

- [ ] **Step 1: Search for stale Kali-only statements**

Run:

```bash
rg -n 'Kali|kali-rootfs|three|apt update|first apt' README.md CLAUDE.md CONTRIBUTING.md docs/guide
```

Expected: identify every user-facing statement that still describes a Kali-only guest or apt-only first boot.

- [ ] **Step 2: Update the documentation**

Replace the artifact table and build instructions with the ten fixed rootfs names, describe runtime selection/download, document the per-distro package commands, and state that QEMU is the default real-device validation path. Keep both English and Chinese UI resource files synchronized; no new user-facing strings are required.

- [ ] **Step 3: Verify documentation consistency**

Run the same search plus a fixed-asset count check. Expected: no stale statement claims that only Kali is supported, and all ten asset names appear in the relevant documentation.

- [ ] **Step 4: Commit the documentation slice**

```bash
git add README.md CLAUDE.md CONTRIBUTING.md docs/guide/getting-started.html docs/guide/packages.html
 git commit -m "docs: describe ten-distro apk support"
```

### Task 8: Build, test, and perform device validation

**Files:**
- No source files expected.
- Build outputs remain ignored.

**Interfaces:**
- Produces `app/build/outputs/apk/debug/app-debug.apk` or the signed release APK.
- Produces device evidence for QEMU boot on all ten distros; AVF evidence only when supported.

- [ ] **Step 1: Run local static and unit verification**

```bash
for f in fetch-artifacts.sh scripts/verify-rootfs-assets.sh; do bash -n "$f"; done
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Expected: all tests pass and the Debug APK exists. If the local SDK is unavailable, record the exact SDK error and use CI as the build gate.

- [ ] **Step 2: Push the implementation and run GitHub Actions**

Push the task commits to `main`. Require the workflow's asset check, unit tests, and APK build to pass before device installation.

- [ ] **Step 3: Install on the connected arm64 device**

Use the locally built APK when it exists; otherwise download the successful workflow artifact to `app/build/outputs/apk/debug/app-debug.apk` before installing:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm list features | grep virtualization || true
```

Record the device model, Android API, ABI, and whether virtualization support is present. If no device is listed, stop the hardware phase and report that exact blocker.

- [ ] **Step 4: Validate all ten QEMU selections**

For each enum selection in this order:

```text
KALI, DEBIAN, UBUNTU, FEDORA, ROCKY, ALMA, OPENSUSE, ARCH, MANJARO, GENTOO
```

Select the distro in the first-run wizard, start the download, wait for the fixed rootfs file, start QEMU, wait for `Ready!`, and confirm terminal access. For one non-Kali selection, also paste a custom rootfs URL and confirm the app still stores it under the selected asset filename. Reset the VM between selections. Capture:

```bash
adb logcat -d -s PodroidQemu
adb shell run-as com.excp.podroid.debug cat files/console.log
```

- [ ] **Step 5: Validate AVF when supported**

If `pm list features` reports `android.software.virtualization_framework`, grant `MANAGE_VIRTUAL_MACHINE` and `USE_CUSTOM_VIRTUAL_MACHINE`, force-stop and relaunch the app, then repeat the selected-distro boot flow. If the feature is absent, mark AVF as unavailable on this device and do not call it a code failure.

- [ ] **Step 6: Final verification and report**

Run the complete unit suite again, inspect `git status`, and report exact APK path, CI run URL, device serial/model, tested distro list, backend, and any untestable AVF condition. Do not claim ten-device support until all ten QEMU boots reach `Ready!`.
