#!/bin/bash
cd "$(dirname "$0")" && ./build.sh && adb install -r build/launcher.apk \
  && adb shell cmd package compile -m speed -f app.fastlauncher \
  && adb shell cmd role add-role-holder android.app.role.HOME app.fastlauncher \
  && adb shell cmd package set-home-activity app.fastlauncher/.MainActivity \
  && adb shell input keyevent KEYCODE_HOME
