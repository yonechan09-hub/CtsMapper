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

## Battery friendly

- No polling and no wake locks.
- Key filtering, the edge strip and the key listener are switched off whenever
  nothing needs them.
- The Plus Key listener runs only while the screen is on, and only if a Plus
  Key action is set.
- There is no foreground service, and no notification unless you turn on the
  optional status notification.
- A master switch turns everything off at once.

## Quick Settings tile

Add the **CTS Mapper** tile to your Quick Settings panel to switch the
accessibility service on or off with one tap. This is useful for apps that
refuse to run while an accessibility service is on. It only acts when you tap
it, never on its own. The tile needs `WRITE_SECURE_SETTINGS`, which you grant
once from the Setup tab with Shizuku.

## Keeping it running

- **Auto-recover (off by default):** if the accessibility service shows "Not working" or gets
  killed, the app switches it off and on again to bring it back.
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

An in-app log shows the last events (triggers, actions, errors). A **Copy for
support** button copies the log together with device and settings info.

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
the key, then pick actions for it. The app only observes the key, so ColorOS'
own Plus Key action still fires unless you set it to nothing in system settings.

## Known issues

- The accessibility switch inside the app can sometimes hang. If that happens,
  turn the service on or off from Android's Accessibility settings instead.

## License

All rights reserved. See the LICENSE file.
Third-party notices: see THIRD_PARTY_NOTICES.md.
