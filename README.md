# CTS Mapper

Run **Circle to Search** or **Gemini** from a gesture, a volume button, or a
hardware key such as the ColorOS **Plus Key**. Made for ColorOS, but it should
work on other Android 11+ phones too.

## Triggers

| Trigger | How |
|---|---|
| Gesture bar | Long press the gesture bar at the bottom of the screen. A small invisible strip (height adjustable) sits on top of it. |
| Volume Up / Volume Down | Long press or double press. A normal short press still changes the volume. |
| Recorded key (Plus Key / side button) | Press or long press. You record the key once, and the app remembers it. |

## Actions

Each trigger can run any of these:

- **Circle to Search**
- **Gemini overlay** (the "Ask Gemini" pill)
- **Gemini via the ASSIST intent** (opens the Google app)
- **Gemini app** (full screen)

The Gemini overlay has three variants you can switch between, in case one
doesn't work on your ROM. A Test button next to each trigger runs its action
right away.

## Plus Key (recorded key) listener

The Plus Key can't be seen by normal apps, so CTS Mapper reads it through a
small helper that Shizuku starts.

- **Recording:** while the Record screen is open, the helper listens on every
  readable input device, with no filtering. Press the key and it is recorded as
  the device name plus the key code. Volume and power are ignored.
- **Normal use:** the helper listens only on the device you recorded. If that
  device can't be found or opened, it logs that and stays idle (it never opens
  all devices outside recording). Record the key again to fix it.
- **Screen off:** it only runs while the screen is on, and only if a Plus Key
  action is set.
- **Failures are contained:** if one input device can't be opened or read, the
  helper skips it and keeps going with the rest. Exceptions are caught and
  written to the Log, never allowed to crash the helper.
- **Auto-reconnect:** if the connection to the helper drops while Shizuku is
  running, the app retries after 1 s, 2 s, 4 s, 8 s and 16 s, then gives up. A
  bind that doesn't answer within 10 s counts as a failed attempt. When Shizuku
  itself is not running there are no timers: the app waits for Shizuku to
  announce itself and starts the helper then. Turning the listener off cancels
  the retries.
- **Helper lifetime:** it is kept only while a Plus Key mapping is enabled, or
  while Auto-recover is on and Shizuku is already running.
- The app only observes the key. ColorOS' own Plus Key action still fires
  unless you set it to nothing in system settings.

## Battery friendly

- No polling and no wake locks.
- Key filtering, the edge strip and the key listener are switched off whenever
  nothing needs them.
- The Plus Key listener runs only while the screen is on, and only if a Plus
  Key action is set.
- Auto-recover is off by default. It is event driven: no watcher, no polling and
  no process spawned on app switches. One debounced check runs when the app's
  connection to the helper dies, or when the app sends the Auto-recover setting.
  The check and the toggle run in a single shell call.
- There is no foreground service, and no notification unless you turn on the
  optional status notification.
- A master switch turns everything off at once.

## Quick Settings tile

Add the **CTS Mapper** tile to your Quick Settings panel to switch the
accessibility service on or off with one tap. This is useful for apps that
refuse to run while an accessibility service is on. It only acts when you tap
it, never on its own. The tile needs `WRITE_SECURE_SETTINGS`, which you grant
once with **Grant via Shizuku** (Setup tab or main screen). The granted state
updates on screen right away, with no restart.

## Keeping it running

- **Auto-recover (off by default):** if the accessibility service shows "Not
  working" or gets killed, the app switches it off and on again to bring it
  back. Turn it on in the Keep-alive card. It only acts while the service is
  enabled, so it never turns on a service you switched off yourself.
- **Live status:** the accessibility switch and status pill on the main screen
  and in the Setup guide update as soon as the service is turned on or off,
  from the app, the tile, or Android's settings.
- **Setup guide** with optional ColorOS steps that help stop the system from
  killing or freezing the app: battery set to Unrestricted, Auto-launch, lock
  in recents, and the Google and Gemini apps. You may not need all of them. Try
  the app first and only apply the ones that fix problems on your phone.

## Shortcuts for other apps

Run an action from Tasker, a launcher shortcut, or ColorOS' own key settings:

    ctsmapper://run/CTS
    ctsmapper://run/GEMINI_OVERLAY
    ctsmapper://run/GEMINI_ASSIST
    ctsmapper://run/GEMINI_APP

## Log and diagnostics

An in-app log shows the last 150 events, newest first. Besides triggers,
actions and errors it shows:

- **Helper** lines: which input devices were opened (and which failed, with the
  reason), and when the helper falls back to all devices.
- **Key** lines: key presses and releases from the recorded device (or any
  device while recording), at most one per second.
- **Error** lines: the exact exception (class, message, file and line) for any
  failure in the app or the helper, and why the helper was stopped.

A **Copy for support** button copies the log together with device and settings
info.

## Requirements

- Android 11 or newer
- Accessibility service turned on for CTS Mapper
- Google set as the default assistant (needed for Circle to Search)
- [Shizuku](https://shizuku.rikka.app/) running, only for the Plus Key listener
  and for granting permissions once

## Install

1. [Download CTS-Mapper.apk](https://github.com/yonechan09-hub/CtsMapper/releases/latest/download/CTS-Mapper.apk)
   (also available on the Releases page, right side of this page).
2. Install it and open the app.
3. Turn on the accessibility service first from Android's own settings
   (Settings > Accessibility > CTS Mapper), not from the switch inside the app.
   The in-app switch can sometimes hang.
4. Follow the rest of the **Setup** tab.

For the Plus Key: turn on the Shizuku listener, tap **Record trigger**, press
the key, then pick actions for it.

**Updates:** every build is signed with the same fixed key, so a new APK
installs over the old one and keeps your settings. If you have an older build
that was signed with a different key, uninstall it once first.

## Troubleshooting the Plus Key

- Open the Log after pressing the key. **Helper** lines show which devices were
  opened. If none were, Shizuku isn't running or isn't allowed.
- **Key** lines show what the helper sees. If your key never appears there, the
  system doesn't hand it to apps on this phone.
- If the key is seen but nothing runs, check that a press or long-press action
  is set and that the recorded device name matches the one in the Key lines.

## Known issues

- The accessibility switch inside the app can sometimes hang. If that happens,
  turn the service on or off from Android's Accessibility settings instead.

## License

All rights reserved. See the LICENSE file.
Third-party notices: see THIRD_PARTY_NOTICES.md.
