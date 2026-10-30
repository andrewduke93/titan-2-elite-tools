# Titan Gamepad

A virtual controller driver for the Unihertz Titan 2 Elite. It translates
the phone's own physical QWERTY keyboard into a real UHID device, so any
app that supports a controller — emulators, games, streaming apps — sees
a controller, not a keyboard. No Titan Boy shell, no RetroArch fork,
no root.

It has two profiles in one app, cycled from the single launcher icon:

**Off → Controller → Mouse → Off**

Each launch moves to the next profile and shows a short toast naming it:
"Controller mode.", "Mouse mode.", or "Off." The toast lasts about a
second and cancels the previous one, so rapid cycling does not stack
toasts. Press X at any time to hear the current mode (note: that toast
comes from the service, so it does not always show).

## Mouse mode

The keyboard becomes a real UHID mouse. Android shows the system cursor
while the mouse device exists and hides it when you switch away from
mouse mode — no overlay code is needed.

![Mouse mapping](TitanGamepad-mouse-mapping-1to1.jpg)

- Move: **W / A / S / D** (hold to keep moving; speed ramps up)
- Clicks: **O = left click, P = right click**
- No scroll keys. I, J, K, and L are unmapped in mouse mode and just
  pass through.

### Mouse pauses in text fields

While mouse mode is on and an editable text field has focus, mouse mode
pauses automatically: WASD, O, and P just type normally, no mouse
movement or clicks are sent, and any held movement or click is cleared
the moment you enter the field. When focus leaves the field (you tap a
non-editable view, or switch windows), mouse mode resumes on its own —
no relaunch needed.

This needs the service to be able to see whether the focused view is
editable, so after installing a build that changes that config, toggle
Titan Gamepad off and on once in Accessibility settings so the new
config is read. Controller mode does not pause in text fields — this
pause is mouse mode only.

## Controller mode

The keyboard becomes a real UHID gamepad. Cannoli was just how it was
tested and verified; nothing in the driver is specific to one emulator.

![Controller mapping](TitanGamepad-mapping-1to1.jpg)

- D-pad: **W / A / S / D** (hat switch; diagonals work)
- Face: **K = A, L = B, J = X, I = Y**
- Shoulders: **Q = L, E = R, C = L2, N = R2, T = L3, Y key = R3**
- **Enter = Select, Space = Start, M = Menu** (Cannoli's In-Game Menu)
- **O** toggles WASD between D-pad and left stick
- **P** toggles KLJI between face buttons and right stick
  (right stick by position: I up, J left, K down, L right)

Button bits follow Cannoli's gamepad tester order (it counts C and Z as
buttons, so X is bit 3, Y is bit 4, L is bit 6, R is bit 7, Select is bit 10,
Start is bit 11, Mode is bit 12, L3 is bit 13, R3 is bit 14). L3/R3 bits are
an extrapolation — they have not been tester-verified.

## No UI, no blocking

None of these apps have a UI. They are toggled from the Titan 2 Elite's
built-in keyboard shortcut settings.

No keys are blocked. Mapped keys send their gamepad or mouse input and
also pass through; unmapped keys send blank input (no gamepad or mouse
input for that key) and pass through. That is deliberate: an earlier
version that consumed keys broke gamepad button release pairing on this
phone, so the driver now never consumes a key. The practical consequence
is that, in a text field, gamepad keys will also type — in a game or a
gamepad tester that does not matter, and in mouse mode the text-field
pause above handles the typing case. If a device fails to start, keys
pass through normally instead, as always.

## Modes are always known

Every time controller mode starts, it starts in D-pad + face-button mode.
Stick modes do not persist silently across toggles.

## Stuck directions

If a key-up event is ever lost, a direction can stick. Two things fix it
without toggling the driver off:

1. Tap the stuck key again — a fresh press for an already-pressed direction
   clears it (held-key repeats are filtered out, so normal holds are safe).
2. Press O to flip the stick mode — flipping clears the WASD state.

Toggling the driver off sends a neutral report and clears all state. If a
menu (Cannoli's, especially) keeps scrolling after the driver is off, that
is the menu latching its last input — force-closing the app clears that side.

## Permissions

1. Accessibility: Titan Gamepad ON. Android will warn it can observe keys
   and retrieve window content. Keys are observed only to send gamepad or
   mouse input for mapped keys (nothing is blocked, see above), and window
   content is checked only for whether the focused view is editable, so
   mouse mode can pause in text fields. There is no network code.
2. Shizuku: grant Titan Gamepad in the Shizuku app, so it can create the
   UHID device. Shizuku must be running; after a reboot, restart it.

## Safety rule

Keys always pass through, whether or not a device is running. If device
creation fails, no gamepad or mouse input is sent — the driver never
steals a keyboard it cannot replace, and it never blocks one either.

## How it works

- `GamepadAccessibilityService.java` — input path, profiles, mode
  cycling, state, the mouse movement ticker, and the mouse input-field
  pause (focused-view editable check).
- `GamepadMapping.java` — the controller key-to-button table and
  pressed state.
- `UhidDevice.java` — minimal UHID gamepad, written from the kernel
  `uhid.h` header (CREATE2=11, INPUT2=12, 4376-byte events).
- `UhidMouseDevice.java` — minimal UHID mouse (3 buttons, relative
  X/Y, wheel), same UHID framing.
- `UhidHelper.java` / `UhidMouseHelper.java` — the same device code run
  as the shell user via Shizuku's `app_process`, fed `STATE …` lines.
  This is the path that actually works on this phone; direct `/dev/uhid`
  from a normal app is almost never openable.
- `GamepadActivity.java` — the silent launcher that cycles the profiles.

## Test steps

1. Sideload, grant Accessibility + Shizuku. If you just updated from an
   older build, toggle Titan Gamepad off and on once in Accessibility
   settings so the input-field pause config is read.
2. Launch the Titan Gamepad icon once — it should toast
   "Controller mode." Launch again for "Mouse mode." (cursor appears),
   again for "Off."
3. In controller mode, test every button in a gamepad tester, then a
   simple game with gamepad input (not keyboard input). Note that keys
   also pass through, so in a text field they will type as well — that
   is expected, not a bug.
4. In mouse mode, test movement and both clicks (O left, P right).
5. In mouse mode, focus a text field: movement and clicks must stop and
   keys must type normally. Tap a non-editable view to resume mouse
   mode without relaunching.
