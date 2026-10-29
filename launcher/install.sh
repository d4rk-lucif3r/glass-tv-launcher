#!/bin/zsh
# Build the launcher (release, R8-optimized) and install it on the TV with everything it needs.
# usage: ./install.sh <tv-ip>      (or set TV_IP)   e.g. ./install.sh 192.168.1.50
set -e
TV=${1:-${TV_IP:?usage: ./install.sh <tv-ip>  (enable ADB / network debugging on the TV first)}}:5555
PKG=com.arshanwar.tv_launcher
cd "$(dirname $0)"
export JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

adb connect $TV >/dev/null
./gradlew --no-daemon -q :app:assembleRelease
adb -s $TV install -r app/build/outputs/apk/release/app-release.apk
s(){ adb -s $TV shell "$@"; }

# adb-only permissions (development permissions can't be requested at runtime)
s pm grant $PKG android.permission.DUMP                   # Wi-Fi name via dumpsys
s pm grant $PKG android.permission.READ_LOGS              # remote Home/PatchWall button watcher
s pm grant $PKG android.permission.WRITE_SECURE_SETTINGS  # re-arms its own accessibility service
s pm grant $PKG android.permission.READ_EXTERNAL_STORAGE    # Files panel
s pm grant $PKG android.permission.WRITE_EXTERNAL_STORAGE   # received files -> Download/Arsh TV
s appops set $PKG GET_USAGE_STATS allow                  # "Recently used" shelf
s appops set $PKG REQUEST_INSTALL_PACKAGES allow         # install APKs from Files / Drop
s pm grant $PKG android.permission.RECORD_AUDIO           # voice search

# default home + screensaver + Home-button service
s cmd package set-home-activity $PKG/.MainActivity >/dev/null
s settings put secure screensaver_components $PKG/$PKG.LockDream
s settings put secure screensaver_enabled 1
s settings put secure enabled_accessibility_services $PKG/$PKG.KeyService
s settings put secure accessibility_enabled 1

# installing resets ART compilation: compile everything ahead of time (~2-5x faster start, smoother frames)
s cmd package compile -m speed -f $PKG

# (no auto-launch: the TV may be in use)
echo "installed, compiled and armed on $TV"
