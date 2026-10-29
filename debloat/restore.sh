#!/bin/zsh
# Restore a package removed with --user 0:  TV_IP=192.168.1.50 restore.sh <pkg>
: ${TV_IP:?set TV_IP to your TV's address}
adb -s $TV_IP:5555 shell cmd package install-existing "$1"
