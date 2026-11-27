#!/usr/bin/env bash
# The widget audit (27 Nov 2026): every Cerniera widget, hosted for real by Banco, over one
# wallpaper ($WALL: still, pattern or live), on a main-screen and a cover-screen sized display,
# in every finish, with long real-world calendar titles. Photographs go to audit/.
#
# Display sizes are approximations of the Fold's two screens (not measured from Mechack's phone).
set -u
WALL=${WALL:-still}
OUT=audit; mkdir -p $OUT
FW=com.fergusonconsultancy.foldwidgets
B=com.cerniera.banco
BF=/sdcard/Android/data/$B/files
SEQ=0
log() { echo "$*" | tee -a $OUT/run-$WALL.txt; }
# adb shell joins its arguments for the phone's shell: quote each one so | ; ( and spaces survive
q() { local a out=""; for a in "$@"; do out="$out '${a//\'/}'"; done; printf '%s' "$out"; }
sh_() { adb shell "$(q "$@")"; }

adb root >/dev/null 2>&1; sleep 3; adb wait-for-device
adb shell setprop persist.sys.timezone Europe/London
adb shell settings put global auto_time 1 >/dev/null 2>&1

for a in apks/*.apk; do adb install -r -g "$a" >/dev/null 2>&1 && log "installed $(basename $a)" || log "FAILED to install $(basename $a)"; done

# Permissions as on Mechack's phone: calendar, location, notification access, all-files (Glass), live mode
for p in android.permission.READ_CALENDAR android.permission.ACCESS_COARSE_LOCATION android.permission.ACCESS_FINE_LOCATION android.permission.POST_NOTIFICATIONS; do
  for pkg in $FW com.cerniera.settimana com.cerniera.nodo; do adb shell pm grant $pkg $p >/dev/null 2>&1; done
done
adb shell pm grant $FW android.permission.ACCESS_BACKGROUND_LOCATION >/dev/null 2>&1
adb shell appops set $FW MANAGE_EXTERNAL_STORAGE allow
adb shell cmd notification allow_listener $FW/$FW.MediaListener
adb shell settings put secure enabled_accessibility_services $FW/$FW.LiveService
adb shell settings put secure accessibility_enabled 1
adb emu geo fix -1.5491 53.8008 >/dev/null 2>&1
adb shell appwidget grantbind --package $B --user 0
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher >/dev/null 2>&1
adb shell cmd package set-home-activity --user 0 $B/.Bench
log "home: $(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1)"
log "widget bitmap limit is set at boot from the display: $(adb shell wm size | tr '\n' ' ')"

# A calendar with the kind of titles Mechack's has: long, specific, some all-day
sh_ content insert --uri "content://com.android.calendar/calendars?caller_is_syncadapter=true&account_name=audit&account_type=LOCAL" \
  --bind account_name:s:audit --bind account_type:s:LOCAL --bind name:s:Audit --bind calendar_displayName:s:Audit \
  --bind calendar_color:i:-1689775 --bind calendar_access_level:i:700 --bind ownerAccount:s:audit --bind visible:i:1 --bind sync_events:i:1
CAL=$(adb shell content query --uri content://com.android.calendar/calendars --projection _id | grep -o '_id=[0-9]*' | tail -1 | cut -d= -f2)
DAY0=$(( ($(adb shell date +%s | tr -d '\r') / 86400) * 86400 ))
ev() { # day-offset hour minute duration-min title [allday]
  local s=$(( (DAY0 + $1 * 86400 + $2 * 3600 + $3 * 60) * 1000 )) e
  if [ "${6:-}" = allday ]; then s=$(( (DAY0 + $1 * 86400) * 1000 )); e=$(( s + 86400000 ))
    sh_ content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CAL --bind dtstart:l:$s --bind dtend:l:$e \
      --bind allDay:i:1 --bind eventTimezone:s:UTC --bind "title:s:$5" >/dev/null
  else e=$(( s + $4 * 60000 ))
    sh_ content insert --uri content://com.android.calendar/events --bind calendar_id:i:$CAL --bind dtstart:l:$s --bind dtend:l:$e \
      --bind eventTimezone:s:Europe/London --bind "title:s:$5" >/dev/null
  fi
}
ev 0 13 0 60 "Lunch with Sam at the Corn Exchange, then a walk back through Briggate"
ev 0 17 30 60 "Client call: Section 166 skilled person review, weekly status"
ev 1 12 0 120 "Arsenal vs Bournemouth U18 (H) - Sobha Realty Training Centre"
ev 2 0 0 0 "First Sunday of Advent" allday
ev 2 16 30 120 "Arsenal vs Manchester United - Premier League, Emirates Stadium"
ev 3 19 0 120 "Blackburn Rovers vs Leeds United - Championship, Ewood Park"
ev 4 9 30 30 "Dentist"
ev 5 18 30 90 "Five-a-side at Goals Leeds"
log "calendar $CAL seeded from $(date -u -d @$DAY0): $(adb shell content query --uri content://com.android.calendar/events --projection title | grep -c Row) events"

cmd() { # waits for Banco to finish a command
  SEQ=$((SEQ + 1)); adb shell rm -f $BF/done-$SEQ >/dev/null 2>&1
  sh_ am start -n $B/.Bench --es seq $SEQ "$@" >/dev/null
  for i in $(seq 1 40); do adb shell "[ -f $BF/done-$SEQ ]" && break; sleep 0.5; done
  adb shell "[ -f $BF/error-$SEQ ]" && log "Banco error: $(adb shell cat $BF/error-$SEQ | head -3)"
}
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
tap_text() { # taps the first view whose text or description is $1, scrolling down to find it
  local want="$1" i b
  for i in $(seq 1 40); do
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    b=$(adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep -E "(text|content-desc)=\"$want\"" | head -1 | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | tr '\n' ' ')
    if [ -n "$b" ]; then set -- $b; adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 )); return 0; fi
    adb shell input swipe 500 1700 500 500 300; sleep 0.8
  done
  return 1
}

# First opens, so each app sets itself up (Dorso's sample read, Nodo's thread)
cmd --es cmd providers
for a in $FW/.SettingsActivity com.cerniera.nodo/.MainActivity com.cerniera.dorso/.MainActivity com.cerniera.riserva/.MainActivity com.cerniera.settimana/.MainActivity; do
  adb shell am start -n $a >/dev/null 2>&1; sleep 4
done
adb pull $BF/providers.txt $OUT/providers.txt >/dev/null
log "providers:"; cat $OUT/providers.txt | tee -a $OUT/run-$WALL.txt
P() { grep -m1 "$1" $OUT/providers.txt | cut -f1; }
AGL=$(P "/.AgendaLarge"); AGS=$(P "/.AgendaSmall"); MUL=$(P "/.MusicLarge"); MUS=$(P "/.MusicSmall"); WEL=$(P "/.WeatherLarge"); WES=$(P "/.WeatherSmall")
MOL=$(P "/.MonthLarge"); MOM=$(P "/.MomentoWidget"); NODO=$(P "^com.cerniera.nodo/"); DORSO=$(P "^com.cerniera.dorso/"); RIS=$(P "^com.cerniera.riserva/"); SET=$(P "^com.cerniera.settimana/")

# The wallpaper
case $WALL in
  still|pattern)
    # Into Banco's own files, through run-as, so it can read it
    adb push walls/$WALL.png /data/local/tmp/$WALL.png >/dev/null; adb shell chmod 644 /data/local/tmp/$WALL.png
    adb shell run-as $B sh -c "'mkdir -p files && cp /data/local/tmp/$WALL.png files/$WALL.png'"
    cmd --es cmd wall --es file $WALL.png ;;
  live)
    adb shell am start -a android.service.wallpaper.CHANGE_LIVE_WALLPAPER \
      --ecn android.service.wallpaper.extra.LIVE_WALLPAPER_COMPONENT com.fergusonconsultancy.solco/.SolcoWallpaperService >/dev/null
    sleep 6; shot "picker-$WALL"
    tap_text "Set wallpaper" || tap_text "SET WALLPAPER" || log "live wallpaper: no Set button found"
    sleep 2; tap_text "Home and lock screens" || tap_text "Home screen" || true
    sleep 4 ;;
esac
adb shell input keyevent KEYCODE_HOME; sleep 2

FINISHES=("Vetro frost" "Cristallo clear" "Goccia liquid" "Smoked glass" "Membrane" "Rosso lacquer" "Avorio" "Carbonio" "Ceramica" "Visconti" "Paper" "Ottone")
SLOT_FINISHES=("Vetro frost" "Cristallo clear" "Goccia liquid" "Smoked glass" "Rosso lacquer" "Ceramica" "Ottone")

finish() {
  adb shell am start -n $FW/.SettingsActivity >/dev/null; sleep 3
  if tap_text "$1"; then log "finish: $1"; else log "finish NOT FOUND: $1"; adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb pull /sdcard/ui.xml "$OUT/ui-notfound-$WALL.xml" >/dev/null 2>&1; shot "notfound-$WALL"; fi
  sleep 2; adb shell input keyevent KEYCODE_HOME; sleep 1
}
# Places a scene, lets the apps draw and live mode take its photograph, then photographs it
scene() { # name spec
  cmd --es cmd place --es spec "$2"
  sleep 9; shot "$1"
  adb pull $BF/bounds-$SEQ.txt "$OUT/$1.txt" >/dev/null 2>&1
}

for SCREEN in main cover; do
  if [ $SCREEN = main ]; then adb shell wm size 1968x2184; else adb shell wm size 1080x2520; fi
  adb shell wm density 420; sleep 4
  adb shell input keyevent KEYCODE_HOME; sleep 2
  cmd --es cmd clear; sleep 2; shot "$WALL-$SCREEN-bare"
  for F in "${FINISHES[@]}"; do
    finish "$F"; f=$(echo "$F" | cut -d' ' -f1 | tr 'A-Z' 'a-z')
    if [ $SCREEN = main ]; then
      scene "$WALL-$SCREEN-$f-a" "agenda|$AGL|16|48|255|361;weather|$WES|287|48|255|267;settimana|$SET|558|48|177|300;nodo|$NODO|16|429|255|164;riserva|$RIS|287|335|255|164;dorso|$DORSO|558|368|177|226"
      scene "$WALL-$SCREEN-$f-b" "music|$MUL|16|48|385|226;momento|$MOM|417|48|300|226;month|$MOL|16|294|255|267;settimana|$SET|287|294|385|226;weatherL|$WEL|16|581|385|226;nodo|$NODO|417|540|300|164"
    else
      scene "$WALL-$SCREEN-$f-a" "music|$MUS|13|48|385|226;settimana|$SET|13|290|385|226;nodo|$NODO|13|532|192|164;riserva|$RIS|206|532|192|164;dorso|$DORSO|13|712|192|164;weather|$WES|206|712|192|164"
    fi
  done
done

# The same place, the same size, the same finish, one app after another: their tones must match
adb shell wm size 1968x2184; adb shell wm density 420; sleep 4
for F in "${SLOT_FINISHES[@]}"; do
  finish "$F"; f=$(echo "$F" | cut -d' ' -f1 | tr 'A-Z' 'a-z')
  for w in "fw-weather|$WES" "fw-agenda|$AGS" "fw-music|$MUS" "nodo|$NODO" "dorso|$DORSO" "riserva|$RIS" "settimana|$SET"; do
    n=${w%%|*}; scene "$WALL-slot-$f-$n" "$n|${w#*|}|287|335|255|164"
  done
done
adb logcat -d -s Cerniera:I FoldWidgets:I Nodo:I Banco:I Dorso:I Riserva:I Settimana:I > $OUT/log-$WALL.txt 2>/dev/null
adb logcat -d "*:E" | grep -E "cerniera|foldwidgets|AppWidget|RemoteViews" > $OUT/errors-$WALL.txt 2>/dev/null
adb logcat -b crash -d > $OUT/crash-$WALL.txt 2>/dev/null
log "done"
