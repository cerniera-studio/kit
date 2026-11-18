# Kit

Cerniera's shared design system: the family's colours, type, motion curves and controls, written once
and pulled into every app. A change here reaches every app in its next build.

## What's in it

- **Kit.kt**: tokens for both lights (Visconti by night, Avorio by day), Italiana and Jost, the motion
  curves (`ease`, `lead`, `trail`), durations, and the Bellezza Necessaria doctrine text.
- **Controls.kt**: `Screen` (with the Membrane fling and Concentrato fade built in), `Card`, `Row`,
  `Toggle`, `Segmented` (liquid thumb), `ChoiceList`, `Slider`, `Chips`, `Fold`, `primary`,
  `figure`, and `doctrineCard` for the foot of each app's settings.
- **Hinge.kt**: the hinge angle sensor, fold speed (fast above 320 °/s), and open/closed events.
- **Sky.kt**: the family's one sky. `Sky.sun(place, instant)` gives the sun's azimuth and altitude;
  `Sky.day(place, date, zone)` gives civil dawn, sunrise, the end of morning golden hour, solar
  noon, the start of evening golden hour, sunset and civil dusk; `Sky.moon(instant)` gives the
  phase. NOAA's equations, pure arithmetic, no permissions. Apps pass the phone's location and fall
  back to `Sky.Place.LEEDS`. Tested against pysolar for Leeds (`gradle :kit:testDebugUnitTest`).
- **Senso** (Fluido phase 0, 18 Nov 2026): the family's context engine, rules only.
  - `SensoRules.kt`: pure rules over a snapshot of signals, giving six moments, each with a
    confidence and the lines that built it: leaving soon, rain coming, listening, winding down,
    waking, reading. `Rules.pick` offers one at a time, only above 60%, holds what it offered
    against flicker and never offers a kind dismissed today. Tested on the JVM.
  - `Senso.kt`: gathers the signals on events it already gets (power, audio outputs, the calendar
    changing, Cielo's forecast changing, the screen coming on) and wakes only at the next time a
    moment could begin or end. The app feeds what the Kit can't see (the app in front, what is
    playing, home or away). Rain is read from Cielo's provider, `content://com.cerniera.cielo.rain/next`
    (one row: `starts_at` epoch ms, `chance` 0–1, `mm`), once Cielo shares it.
  - `SensoView.kt`: the debug view: what is offered, every moment against the threshold, every
    signal with its source and age, the last changes.
  - Injection, for the emulator, in apps that allow it: `adb shell am broadcast -a
    com.cerniera.senso.INJECT -p <package> --es rain_in 40 --es rain_chance 80` (keys in
    `Senso.INJECT_KEYS`). Only the shell can send it.
- **lab/**: a small app showing Senso's debug view. CI injects thirteen scenarios into it and
  checks what Senso offers (`.github/senso.sh`); screenshots go to the `screens` branch. Apps take
  the Kit as `kit/kit`, so they never build the lab.
- **Crash.kt**: keeps a crash report and shows it on the next open with Share, Copy and Try again.
- **Fonts**: Italiana and Jost, both under the SIL Open Font Licence (texts alongside).

## Using it in an app

The Kit is a git submodule, pinned to a commit, so an app only changes when you move its pin.

```
git submodule add https://github.com/cerniera-studio/kit.git kit
```

`settings.gradle.kts`:

```kotlin
include(":kit")
project(":kit").projectDir = file("kit/kit")
```

Root `build.gradle.kts` also declares `id("com.android.library") version "8.7.3" apply false`.
The app's `build.gradle.kts` adds `implementation(project(":kit"))`, and its CI checkout uses
`submodules: true`.

To take a newer Kit into an app: `git submodule update --remote kit`, then commit the new pin.
