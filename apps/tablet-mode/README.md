# Tablet Mode

Toggles the display density between 210 DPI (tablet mode) and 289 DPI
(normal) on the Titan 2 Elite, via Shizuku. Launch it, the density flips,
it verifies the change stuck (retrying once if it didn't), and it exits —
no screen, no toast in its final form.

Confirmed working on a Titan 2 Elite.

Two bugs were fixed getting here, and both are worth knowing if you fork it:

- The Shizuku provider permission must be
  `android.permission.INTERACT_ACROSS_USERS_FULL`, not
  `moe.shizuku.manager.permission.API_V23`.
- The activity must declare
  `configChanges="density|screenSize|smallestScreenSize|orientation|screenLayout|fontScale|keyboardHidden"`,
  or Android recreates the activity on a density change and the toggle
  flips straight back.

Requires Shizuku running, with Tablet Mode granted in the Shizuku app.
