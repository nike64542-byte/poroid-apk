# Contributing to Podroid

Thanks for considering a contribution. Bug reports, feature requests, and pull requests are all welcome.

Before you start, please skim [`CLAUDE.md`](CLAUDE.md). It documents the VM-engine abstraction and both backends, the boot pipeline, every native binary, and the design quirks you need to know to make changes that don't regress.

## Getting started

This repo is the **APK only**. The VM components are split into sibling repos:

- [poroid-kernel](https://github.com/nike64542-byte/poroid-kernel) — custom Linux kernel (`vmlinuz-virt`)
- [poroid-rootfs](https://github.com/nike64542-byte/poroid-rootfs) — initramfs plus ten arm64 rootfs assets
- [poroid-qemu](https://github.com/nike64542-byte/poroid-qemu) — QEMU + native tools (`libqemu*.so`, `qemu-assets.tar.gz`)

```sh
git clone https://github.com/nike64542-byte/poroid-apk.git
cd poroid-apk
./fetch-artifacts.sh fedora   # download one selected rootfs for a local build
```

You will need:

- **Android SDK** with platform 36 + build-tools
- An **arm64 Android device** running **Android 8.0+ (API 26)** for testing

## Build pipeline

The Android app builds with Gradle. VM artifacts (kernel, selected rootfs, QEMU) are fetched from the sibling
repos' GitHub Releases by `./fetch-artifacts.sh` (or by CI), then bundled into
the APK's `assets/` + `jniLibs/`:

```sh
./fetch-artifacts.sh fedora   # download kernel/rootfs/qemu artifacts
./gradlew assembleDebug  # build the APK
./gradlew installDebug   # build + install on a connected device
```

The terminal-emulator JNI is built by Gradle's NDK build from the vendored
`terminal-emulator` module (16 KB page aligned).

Component versions are pinned in each repo, so read the pin rather than
trusting a number written in prose:

| Component | Pinned in |
|---|---|
| Linux kernel | `poroid-kernel` repo build args |
| QEMU | `podroidQemuVersion` in `gradle.properties` |
| Alpine | `ARG ALPINE_RELEASE` in `poroid-rootfs/build-rootfs/Dockerfile.rootfs` |

Or, for the common case where you only changed Kotlin / UI code:

```sh
./gradlew installDebug
```

## Reporting bugs

Please open an issue using the **Bug Report** template. The most useful single thing you can attach is the diagnostic log:

`Settings → Diagnostics → Export Log`

It bundles app version, device model + Android version, settings, and full logcat in one file. If the bug is VM-side, also include the VM console:

```sh
adb shell run-as com.excp.podroid.debug cat files/console.log
```

Note that `run-as` works on debug builds only. Release builds are not `run-as`-able, so use the in-app export there.

## Submitting changes

1. Fork the repository and create a topic branch (`fix/issue-42`, `feature/whatever`).
2. Keep pull requests focused: one fix or one feature per PR.
3. Work through the checklist below before opening the PR.
4. Match the existing code style of the file you are editing.

### Before you open a PR

**Run the unit tests.** Use the `:app:` form; the bare task name does not behave the same way.

```sh
./gradlew :app:testDebugUnitTest
```

**Test on a real arm64 device.** Emulators do not exercise the QEMU and native-binary path the way real hardware does. Unit tests cover pure logic only, so anything touching VM behaviour, the engine boundary, or a user-visible flow is unproven until it runs on hardware.

If your change touches the engine boundary or AVF, note that the two backends need two different devices: only a device reporting `android.software.virtualization_framework` can exercise AVF at all.

```sh
adb shell pm list features | grep virtualization
```

See [Proving a change on a device](CLAUDE.md#proving-a-change-on-a-device) for the grant sequence and which backend needs which device. **Say in the PR which backends you actually tested on.** Backend asymmetry is the most common source of bugs here, so a QEMU-only test is the easiest way to ship a regression.

**Add both translations for any user-facing string.** Every string needs an entry in `app/src/main/res/values/strings.xml` and `app/src/main/res/values-zh/strings.xml`, and must be referenced with `stringResource`. Do not hardcode user-facing text.

**Never rename a DataStore key without a migration.** Saved user state has to survive every release, since updates install in place. The Kotlin identifier is free to change, but the string literal is an on-disk contract: renaming it orphans the stored value and the setting silently reverts to its default on the user's next launch. `DataStoreKeyContractTest` will fail if you change one. If the rename is deliberate, ship a migration that reads the old key and writes the new one, and update the expectation in the same commit.

**Update the docs your change affects.** If it is user-facing, update [`README.md`](README.md). If it changes the architecture, boot pipeline, terminal layer, or kernel options, update [`CLAUDE.md`](CLAUDE.md) too.

### Commit messages

This repository uses [Conventional Commits](https://www.conventionalcommits.org/): `type(scope): summary`, all lowercase.

```
fix(avf): distinguish an absent GPU API from a failed declaration
docs(site): explain how to reach the desktop from another computer
test(data): pin the persisted DataStore key literals
```

The body should explain **why** the change is needed, not restate what the diff does. A reader six months from now needs the reasoning, not a summary they could get from `git show`.

Avoid `fixes #N` or `closes #N` unless you intend the issue to close when the commit lands, since those keywords close issues automatically. Use `for #N` or `addresses #N` to reference without closing.

## Code style

- Kotlin: follow the [official conventions](https://kotlinlang.org/docs/coding-conventions.html).
- Keep it simple. No premature abstractions.
- Match the surrounding file's style. Consistency beats personal preference.
- Comments explain *why*, not *what*. Self-documenting names go further than prose.
- No emoji, and no em dashes, in code, comments, commit messages, or documentation. Use hyphens, commas, colons, or restructure the sentence.

## Project layout

```
Podroid/
├── app/                                  Android application (Jetpack Compose, Hilt)
│   └── src/main/
│       ├── java/com/excp/podroid/
│       │   ├── engine/                   VmEngine interface + both backends
│       │   │   ├── VmEngine.kt           the backend contract
│       │   │   ├── EngineHolder.kt       Hilt binding; picks and routes to a backend
│       │   │   ├── QemuEngine.kt         QEMU/TCG backend, buildCommand()
│       │   │   ├── QmpClient.kt          QMP: port forwards + USB
│       │   │   ├── avf/                  AVF/pKVM backend (reflection, vsock, 9p)
│       │   │   ├── hostbridge/           guest to Android bridge
│       │   │   └── usb/                  USB passthrough (QEMU only)
│       │   ├── service/                  Foreground service owning the VM lifecycle
│       │   ├── data/repository/          DataStore-backed settings, port forwards,
│       │   │                             updates, language, backups, container stats
│       │   ├── util/                     Network, shell quoting, host metrics
│       │   ├── x11/                      X11/VNC viewer engine
│       │   └── ui/                       Compose screens + theme
│       ├── res/values, res/values-zh/    strings (English + Chinese, keep in sync)
│       ├── jniLibs/arm64-v8a/            QEMU, podroid-bridge, podroid-launcher, libslirp (fetched)
│       └── assets/                       kernel, initramfs, squashfs (fetched), fonts, themes
├── terminal-view/, terminal-emulator/    vendored Termux fork (local Gradle modules)
├── fetch-artifacts.sh                    Downloads VM artifacts from sibling repos
└── docs/                                 GitHub Pages site
```

> The kernel (`podroid_kernel.config`, `Dockerfile`), rootfs/initramfs
> (`build-rootfs/`, `init-podroid`, `Dockerfile.initramfs`), and QEMU
> (`podroid-bridge.c`, `podroid-launcher.c`, `Dockerfile`) now live in the
> sibling repos `poroid-kernel`, `poroid-rootfs`, `poroid-qemu`.

## License

By contributing, you agree that your work will be licensed under the **GNU General Public License v2.0**, the same license as the project.
