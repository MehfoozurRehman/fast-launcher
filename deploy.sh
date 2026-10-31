#!/bin/bash
APP_ID="${APP_ID:-com.example.android.apis}"
export APP_ID
cd "$(dirname "$0")" && ./build.sh && adb install -r build/launcher.apk \
  && adb shell pm grant "$APP_ID" android.permission.WRITE_SECURE_SETTINGS \
  && adb shell cmd package compile -m speed -f "$APP_ID" \
  && adb shell cmd role add-role-holder android.app.role.HOME "$APP_ID" \
  && adb shell cmd package set-home-activity "$APP_ID/app.fastlauncher.MainActivity" \
  && adb shell input keyevent KEYCODE_HOME
