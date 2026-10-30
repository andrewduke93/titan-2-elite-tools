# Lock

Locks the phone the moment you launch it. That's all it does.

First launch asks for device admin permission, so it can call `lockNow()`.
After that, launching it locks immediately and exits. No Shizuku, no
network, no other permission.

Confirmed working on a Titan 2 Elite. Built for keyboard-shortcut launching:
put it on a key and the phone locks instantly.
