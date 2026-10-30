# Building

No Gradle project, no Android Studio setup. Each app is built the same way,
with the Android SDK command-line tools directly.

## Requirements

- JDK 17
- Android SDK with platform `android-34` and build-tools `34.0.0`
- The Shizuku client jars for the Shizuku apps (Titan Gamepad, Tablet Mode,
  Color Invert, Grayscale), placed in the app's `libs/jars/` directory:
  `shizuku-api-classes.jar`, `provider-classes.jar`, `shared-classes.jar`,
  `aidl-classes.jar` — these come from the Shizuku project and are not
  committed here, so the repo stays source-only.
- Lock needs no Shizuku jars.

## Build one app

```sh
cd apps/titan-gamepad   # or tablet-mode, lock, color-invert, grayscale
SDK=~/Android/Sdk JAVA_HOME=~/jdk17 ./build.sh
```

`build.sh` uses `$SDK` (default: `$ANDROID_SDK_ROOT`, falling back to
`~/Android/Sdk`) and `$JAVA_HOME`. It compiles resources with `aapt2`,
compiles the Java sources with `javac`, dexes with `d8`, aligns with
`zipalign`, and signs with a throwaway debug keystore it generates in
`build/` (never committed — see `.gitignore`). The signed APK is written
next to `build.sh`.

Titan Gamepad's `build.sh` preserves its keystore across rebuilds when run
repeatedly in the same tree, so updates install over the previous build
instead of failing on a signature mismatch. For a public release, sign with
your own release key instead.

## Notes per app

- **Titan Gamepad** targets SDK 34. Its Accessibility receiver must be
  registered with `RECEIVER_NOT_EXPORTED` on API 33+ (see
  `GamepadAccessibilityService.java`) — registering it exported is the
  Android 14 crash that originally made Android report the service as
  malfunctioning.
- **Tablet Mode / Color Invert / Grayscale** set the Shizuku provider
  permission to `android.permission.INTERACT_ACROSS_USERS_FULL`. The older
  `moe.shizuku.manager.permission.API_V23` value does not work for the
  provider permission on this ROM.
- Any activity whose setting change can recreate it must declare
  `configChanges="density|screenSize|smallestScreenSize|orientation|screenLayout|fontScale|keyboardHidden"`,
  or a density change will recreate the activity and toggle straight back.
  Tablet Mode learned that one the hard way.
