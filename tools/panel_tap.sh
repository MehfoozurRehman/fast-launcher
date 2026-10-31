#!/bin/bash
adb shell input keyevent KEYCODE_HOME; sleep 0.9
adb shell 'input swipe 900 30 900 1500 250'; sleep 1.1
if [ "$3" = long ]; then adb shell "input swipe $1 $2 $1 $2 900"; else adb shell input tap $1 $2; fi
sleep ${WAIT:-1.8}
adb shell dumpsys window | grep -m1 mCurrentFocus | sed -E 's/.*u0 //; s/\}$//'
