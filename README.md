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
