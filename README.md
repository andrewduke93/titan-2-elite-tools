# Titan 2 Elite Tools

Minimal, single-purpose Android utilities for the Unihertz Titan 2 Elite —
built for a phone with a real physical keyboard, where each app is launched
from a keyboard shortcut and should do exactly one thing, silently, then exit.

No network code in any app. No analytics, no accounts, no data collection.
Each app's source is small enough to read in full before you sideload it.

## The apps

| App | What it does | How it works |
|---|---|---|
| [Titan Gamepad](apps/titan-gamepad/) | Turns the Titan 2 Elite's physical keyboard into a virtual gamepad that any app supporting controllers sees — emulators, games, and streaming apps. One launcher cycles Off → Controller → Off | Accessibility key filtering + UHID virtual device via Shizuku |
| [Tablet Mode](apps/tablet-mode/) | Toggles display density between 210 DPI (tablet mode) and 289 DPI (normal) | Shizuku |
| [Lock](apps/lock/) | Locks the phone the moment you launch it | Device admin (`lockNow`), nothing else |
| [Color Invert](apps/color-invert/) | Toggles display color inversion | Shizuku, flips `accessibility_display_inversion_enabled` |
| [Grayscale](apps/grayscale/) | Toggles grayscale (monochromacy daltonizer) | Shizuku |

None of these apps have a UI. They are toggled from the Titan 2 Elite's
built-in keyboard shortcut settings — assign a key to each app, and
launching it changes the setting (or locks the phone, for Lock), verifies
it stuck, and exits. The only exception is a first-run permission prompt.
Titan Gamepad is the exception to "one thing": its single launcher cycles
Off → Controller → Off. No keys are blocked — mapped keys send
their gamepad input and also pass through, unmapped keys send
blank input and pass through. (Mouse mode was removed 2026-11-03 — it
went unused, so the driver is controller-only now.)

## Status

- **Lock, Tablet Mode, Color Invert, Grayscale, and Titan Gamepad are all
  confirmed working on a Titan 2 Elite** (confirmed 2026-10-30, including
  the Gamepad's short toasts and no-blocking behaviour). The Gamepad's
  mouse profile was removed 2026-11-03 and is not in current builds.

## Install

Prebuilt APKs live under **Releases** — sideload one, grant what it asks for
on first launch, and nothing else is needed:

- Titan Gamepad needs Accessibility permission, plus Shizuku permission
  (grant it in the Shizuku app) so it can create the virtual gamepad
  device. Launch its icon to cycle Off → Controller → Off. After
  installing a build that changes the Accessibility config, toggle
  Titan Gamepad off and on once in Accessibility settings so the new
  config is read.
- Tablet Mode, Color Invert, and Grayscale need Shizuku permission.
- Lock needs device admin permission, granted once on first launch.

Shizuku itself must be running. After a reboot, restart Shizuku as usual.

## Building

See [BUILDING.md](BUILDING.md). In short: JDK 17, Android SDK platform 34,
build-tools 34.0.0, and the Shizuku client jars in each app's `libs/jars/`.
No Gradle, no Android Studio project — each app builds with its `build.sh`
using `aapt2` / `javac` / `d8` directly.

## Trust notes, especially for Titan Gamepad

Titan Gamepad uses an Accessibility service with key filtering. That is the
whole point of the app — it has to see mapped keys so it can send gamepad
input for them — and it is also the permission that deserves the
most scrutiny. The service:

- does **not** block any key: mapped keys send their input and pass
  through, unmapped keys send blank input and pass through, and if a
  device fails to start, keys pass through untouched,
- does **not** retrieve window content: since mouse mode (the only
  feature that checked whether the focused view was editable) was
  removed, `canRetrieveWindowContent` is false. It sends nothing
  anywhere — there is no network code anywhere in this repo to send
  anything anywhere else,
- sends input only to the local UHID device it created.

## Development note

This project was developed with AI assistance. The code was written with
an AI coding assistant working from the owner's on-phone testing and
feedback — including the bug fixes and layout changes that came out of
that testing — and the owner tested every app on a Titan 2 Elite before
release. The source is published in full so anyone can review exactly
what the apps do.

## License

MIT — see [LICENSE](LICENSE).
