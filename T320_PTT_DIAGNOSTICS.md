# Inrico T320 PTT diagnostic test

This phase only logs the physical PTT button. It deliberately does not call Mumla's transmit
code and it does not consume the key event.

## Enable the listener

1. Open Mumla.
2. Open **Settings > Audio > Inrico T320 > Background PTT diagnostics**.
3. In Android's Accessibility settings, enable **Mumla T320 PTT diagnostics**.
4. Return to the Audio settings. The row must say **Enabled**.

## Watch the diagnostic log

```bash
adb logcat -s MumlaT320PTT:E '*:S'
```

Every normal press must produce exactly one `PTT_DOWN` and one `PTT_UP`. Holding the button
must not produce additional `PTT_DOWN` entries from repeated Android DOWN events.

Expected fields include:

```text
event=PTT_DOWN keyCode=229 scanCode=88 action=ACTION_DOWN repeatCount=0 ... consumed=false
event=PTT_UP keyCode=229 scanCode=88 action=ACTION_UP repeatCount=0 ... consumed=false
```

## Test matrix

1. Mumla visible, screen unlocked: press and release PTT three times.
2. Put Mumla in the background: press and release PTT three times.
3. Lock the screen but keep it awake: press and release PTT three times.
4. Turn the display off: press and release PTT three times.

For the lock-screen cases, verify the `interactive` and `keyguardLocked` fields in each log
entry. Preserve the log from any case that produces DOWN without UP, UP without DOWN, or no
event at all.
