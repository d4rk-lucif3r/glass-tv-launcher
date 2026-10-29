#!/bin/zsh
# usage: TV_IP=192.168.1.50 debloat.sh <tier1|tier2|tier3|tier4> [--apply]   (default = dry run)
# Uses `pm uninstall -k --user 0` => reversible with: adb shell cmd package install-existing <pkg>
: ${TV_IP:?set TV_IP to your TV's address}
adbs(){ adb -s $TV_IP:5555 shell "$@"; }
source "$(dirname $0)/packages.sh"
case $1 in tier1) LIST=$TIER1;; tier2) LIST=$TIER2;; tier3) LIST=$TIER3;; tier4) LIST=$TIER4;; *) echo "tier1|tier2|tier3|tier4"; exit 1;; esac
for p in ${=LIST}; do
  if [[ $2 == --apply ]]; then echo -n "$p: "; adbs pm uninstall -k --user 0 $p 2>&1 | tr -d '\r'
  else echo "[dry] would remove $p"; fi
done
