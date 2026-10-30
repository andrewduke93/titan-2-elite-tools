# Titan 2 Elite Tools

Minimal, single-purpose Android utilities for the Unihertz Titan 2 Elite —
built for a phone with a real physical keyboard, where each app is launched
from a keyboard shortcut and should do exactly one thing, silently, then exit.

No network code in any app. No analytics, no accounts, no data collection.
Each app's source is small enough to read in full before you sideload it.

## The apps

| App | What it does | How it works |
|---|---|---|
| [Titan Gamepad](apps/titan-gamepad/) | Turns the Titan 2 Elite's physical keyboard into a virtual gamepad, so emulators (e.g. Cannoli's built-in runner) see a real controller instead of a keyboard | Accessibility key filtering + a UHID virtual gamepad via Shizuku |
| [Tablet Mode](apps/tablet-mode/) | Toggles display density between 210 DPI (tablet mode) and 289 DPI (normal) | Shizuku |
| [Lock](apps/lock/) | Locks the phone the moment you launch it | Device admin (`lockNow`), nothing else |
| [Color Invert](apps/color-invert/) | Toggles display color inversion | Shizuku, flips `accessibility_display_inversion_enabled` |
| [Grayscale](apps/grayscale/) | Toggles grayscale (monochromacy daltonizer) | Shizuku |

All five are designed to be launched from the Titan's keyboard shortcuts, so
they have no UI beyond a first-run permission prompt: launch, change the
setting, verify it stuck, exit.

## Status

- **Lock, Tablet Mode, Color Invert, Grayscale, and Titan Gamepad are all
  confirmed working on a Titan 2 Elite** (confirmed 2026-10-30, including
  the Gamepad menufix build's mode check and stuck-direction fix).

Deliberately **not** included yet: a Scroll Assistant toggle. Clicking its
quick-settings tile crashed SystemUI twice on this ROM, and the tap-based
replacement is untested. It stays out until it is proven safe.

## Install

Prebuilt APKs live under **Releases** — sideload one, grant what it asks for
on first launch, and nothing else is needed:

- Titan Gamepad needs Accessibility permission, plus Shizuku permission
  (grant it in the Shizuku app) so it can create the virtual gamepad.
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
whole point of the app — it has to see mapped keys before the foreground app
does, so it can swallow them and send gamepad input instead — and it is also
the permission that deserves the most scrutiny. The service:

- does **not** read window content (`canRetrieveWindowContent=false`),
- consumes a mapped key **only while the virtual gamepad is actually running**
  (if device creation fails, keys pass through untouched),
- sends input only to the local UHID device it created — there is no network
  code anywhere in this repo to send anything anywhere else.

## Development note

This project was developed with AI assistance. The code was written with
an AI coding assistant working from the owner's on-phone testing and
feedback — including the bug fixes and layout changes that came out of
that testing — and the owner tested every app on a Titan 2 Elite before
release. The source is published in full so anyone can review exactly
what the apps do.

## License

MIT — see [LICENSE](LICENSE).
