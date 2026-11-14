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
- **SkyPlace.kt**: where each app sees the sky from. `SkyPlace.last()` (the phone's last place, else
  Leeds), `SkyPlace.refresh()` (a fresh coarse fix while the app is open, named by the phone's
  geocoder) and `describe()` for a settings row. The app declares `ACCESS_COARSE_LOCATION`.
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
