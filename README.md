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

## Keeping it running

- **Auto-recover:** if the accessibility service shows "Not working" or gets
  killed, the app switches it off and on again to bring it back.
- **Quick Settings tile** to switch the accessibility service on or off in one
  tap, for apps that don't run while an accessibility service is on.
- **Setup guide** with the ColorOS steps: battery set to Unrestricted,
  Auto-launch, lock in recents, and the Google and Gemini apps.

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

1. Download `CTS-Mapper.apk` from the Releases page (right side of this page).
2. Install it and open the app.
3. Follow the **Setup** tab.

For the Plus Key: turn on the Shizuku listener, tap **Record trigger**, press
the key, then pick actions for it. The app only observes the key, so ColorOS'
own Plus Key action still fires unless you set it to nothing in system settings.

## License

All rights reserved. See the LICENSE file.
Third-party notices: see THIRD_PARTY_NOTICES.md.
