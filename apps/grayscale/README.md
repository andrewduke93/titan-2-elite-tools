# Grayscale

Toggles grayscale on the Titan 2 Elite, via Shizuku, using the daltonizer
in monochromacy mode. On enable it sets `accessibility_display_daltonizer`
to 0 and `accessibility_display_daltonizer_enabled` to 1; on disable it
clears only the enabled flag, so the next enable returns to grayscale.
Same silent pattern as Tablet Mode: launch, flip, verify, exit.

**Untested on-phone as of first publish.** If it silently does nothing on
your ROM, please open an issue with your ROM/firmware version.

Requires Shizuku running, with Grayscale granted in the Shizuku app.
