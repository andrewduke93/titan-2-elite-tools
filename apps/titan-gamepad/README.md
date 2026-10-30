# Titan Gamepad

A virtual gamepad driver for the Unihertz Titan 2 Elite. It translates the
phone's own physical QWERTY keyboard into a real UHID gamepad, so emulators
that take gamepad input (e.g. Cannoli's built-in runner) see a controller —
not a keyboard. No Titan Boy shell, no RetroArch fork, no root.

## Why not TitanPad

TitanPad does not work on the Elite, because it reads Titan 2 raw input
devices. This driver is device-independent on purpose: it uses an
Accessibility service with key filtering, which works on any Android
physical keyboard.

## Layout (current build)

- D-pad: **W / A / S / D** (hat switch; diagonals work)
- Face: **K = A, L = B, J = X, I = Y**
- Shoulders: **Q = L, E = R, C = L2, N = R2, T = L3, Y key = R3**
- **Enter = Select, Space = Start, M = Menu** (Cannoli's In-Game Menu)
- **O** toggles WASD between D-pad and left stick
- **P** toggles KLJI between face buttons and right stick
  (right stick by position: I up, J left, K down, L right)
- **X** announces the current mode in a toast, without changing anything

Button bits follow Cannoli's gamepad tester order (it counts C and Z as
buttons, so X is bit 3, Y is bit 4, L is bit 6, R is bit 7, Select is bit 10,
Start is bit 11, Mode is bit 12, L3 is bit 13, R3 is bit 14). L3/R3 bits are
an extrapolation — they have not been tester-verified.

## Modes are always known

Every time the driver turns on, it starts in D-pad + face-button mode, and
the ON toast says so. Stick modes do not persist silently across toggles.
Press X at any time to hear the current mode.

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

1. Accessibility: Titan Gamepad ON. Android will warn it can observe keys —
   that is exactly how mapped keys are consumed. The service does **not**
   read window content, and there is no network code.
2. Shizuku: grant Titan Gamepad in the Shizuku app, so it can create the
   UHID device. Shizuku must be running; after a reboot, restart it.

## Safety rule

A mapped key is consumed **only while the virtual gamepad is actually
running**. If device creation fails, keys pass through untouched — the
driver never steals a keyboard it cannot replace with a gamepad.

## How it works

- `GamepadAccessibilityService.java` — input path, mode toggles, state.
- `GamepadMapping.java` — the key-to-button table and pressed state.
- `UhidDevice.java` — minimal UHID gamepad, written from the kernel
  `uhid.h` header (CREATE2=11, INPUT2=12, 4376-byte events). No TitanPad
  code was copied.
- `UhidHelper.java` — the same device code run as the shell user via
  Shizuku's `app_process`, fed `STATE …` lines. This is the path that
  actually works on this phone; direct `/dev/uhid` from a normal app is
  almost never openable.
- `GamepadActivity.java` — the silent launcher that toggles the driver.

## Test steps

1. Sideload, grant Accessibility + Shizuku.
2. Open a gamepad tester (Cannoli > Controllers). No "Titan Gamepad" yet —
   the driver is off.
3. Launch the Titan Gamepad icon once. The tester should now show it.
4. **Suppression check first:** open a text field with the driver ON and
   press W. No "w" must appear. If a letter appears, turn the driver off
   and report that — do not play with it on, or you will get double input.
5. Test every button in the tester, then a simple game with gamepad input
   (not keyboard input).
6. Launch the icon again to turn the driver OFF before typing elsewhere.
