#!/bin/bash
# Usage: ./build-with-samsung-sdk.sh /path/to/SamsungHealthDataSDK.zip   (or the .aar)
set -e; cd "$(dirname "$0")"; mkdir -p app/libs
case "$1" in *.zip) unzip -o -j "$1" '*libs/samsung-health-data-api*.aar' -d app/libs ;; *.aar) cp "$1" app/libs/ ;; esac
ls app/libs/samsung-health-data-api*.aar
# Requires JDK 17 (JAVA_HOME) and Android SDK (ANDROID_HOME)
: "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK path}"
./gradlew assembleRelease --no-daemon -q
cp app/build/outputs/apk/release/app-release.apk health-sync.apk
"$(ls -d $ANDROID_HOME/build-tools/* | tail -1)/apksigner" verify --print-certs health-sync.apk | grep SHA-1
