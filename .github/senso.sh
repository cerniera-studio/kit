#!/usr/bin/env bash
# Senso in the emulator (Fluido phase 0, 18 Nov 2026). Injects signals into the Kit's lab app, reads
# back what Senso decided (files/senso.json) and photographs the debug view. Each scenario names the
# moment it expects Senso to offer, or none.
set +e
PKG=com.cerniera.kit.lab
APK=$(ls lab/build/outputs/apk/debug/*.apk | head -1)
for i in $(seq 1 30); do [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
sleep 20
adb install -r "$APK"
adb logcat -c
mkdir -p shots
fails=0
shot() { adb exec-out screencap -p > "shots/$1.png"; }
inject() { adb shell "am broadcast -a com.cerniera.senso.INJECT -p $PKG $1" >/dev/null; sleep 2; }
expect() {
  json=$(adb shell run-as $PKG cat files/senso.json | tr -d '\r')
  echo "$json" > "shots/$1.json"
  got=$(echo "$json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["offered"] or "none")' 2>/dev/null)
  shot "$1"
  if [ "$got" = "$2" ]; then echo "::notice::$1: offered $got (as expected)"
  else echo "::error::$1: expected $2, Senso offered ${got:-nothing readable}"; fails=$((fails+1)); fi
}
open() { adb shell am force-stop $PKG; adb shell am start -W -n $PKG/.LabActivity >/dev/null; sleep 5; }

adb shell wm size 823x1920; adb shell wm density 320; sleep 2
open
inject "--ez clear true --es at 14:00";                                                       expect 01-silent none
inject "--ez clear true --es at 14:00 --es rain_in 40 --es rain_chance 80";                   expect 02-rain-soon rain_coming
inject "--ez clear true --es at 14:00 --es rain_in 100 --es rain_chance 50";                  expect 03-rain-far-unlikely none
inject "--ez clear true --es at 14:00 --es event_in 20 --es event_title Dentist --es event_where 'Park Square'"; expect 04-leaving-soon leaving_soon
inject "--ez clear true --es at 14:00 --es event_in 10 --es event_title Stand-up --es event_where 'Microsoft Teams'"; expect 05-a-call-not-leaving none
inject "--ez clear true --es at 14:00 --es playing Teardrop --es artist 'Massive Attack' --es headphones true"; expect 06-listening listening
inject "--ez clear true --es at 14:00 --es playing Teardrop --es paused true --es paused_ago 10 --es headphones true --es headphones_ago 0.5"; expect 07-resume listening
inject "--ez clear true --es at 23:30 --es charging true --es lux 2";                          expect 08-winding-down winding_down
inject "--ez clear true --es at 14:00 --es rain_in 30 --es rain_chance 90";                   expect 09-rain-before-dismissal rain_coming
inject "--es dismiss rain_coming";                                                             expect 10-rain-dismissed none
inject "--es undismiss all";                                                                   expect 11-dismissal-cleared rain_coming
# Held: rain is offered, then music in headphones (surer, but not by enough to take over)
inject "--ez clear true --es at 14:00 --es rain_in 30 --es rain_chance 82";                   expect 12-rain-held-before rain_coming
inject "--es playing Teardrop --es headphones true";                                          expect 13-rain-held-over-music rain_coming

# The debug view in the four shapes, with rain and an event showing (injected after each start:
# a fresh process starts with nothing injected)
for s in "20-cover-portrait 823x1920 320" "21-cover-landscape 1920x823 320" "22-main-portrait 1503x1568 280" "23-main-landscape 1568x1503 280"; do
  set -- $s
  adb shell wm density $3; adb shell wm size $2; sleep 3; open
  inject "--ez clear true --es at 14:00 --es rain_in 40 --es rain_chance 80 --es event_in 70 --es event_title Dentist --es event_where 'Park Square'"
  sleep 1; shot "$1"
  adb shell input swipe 400 1100 400 250 500; sleep 0.8; adb shell input swipe 400 1100 400 250 500; sleep 1.5; shot "$1-signals"
done
adb shell wm size reset; adb shell wm density reset

adb logcat -d > shots/logcat.txt
if grep -q "FATAL EXCEPTION" shots/logcat.txt; then
  grep -A 30 "FATAL EXCEPTION" shots/logcat.txt | grep AndroidRuntime | head -30 | sed 's/^.*AndroidRuntime: //' | while IFS= read -r l; do echo "::error::$l"; done
  fails=$((fails+1))
fi
[ $fails -eq 0 ] && echo "::notice::Senso offered what each scenario expected" || echo "::error::$fails scenario(s) failed"
exit $fails
