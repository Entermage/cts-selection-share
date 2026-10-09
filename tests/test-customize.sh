#!/bin/sh
# Run against customize.sh extracted from the actual ZIP on Linux.
# Installer helpers are mocked: this is not a rooted-device installation test.
set -eu
script_dir=$(CDPATH= cd -- "$(dirname -- "$1")" && pwd)
script="$script_dir/$(basename -- "$1")"

run_install() (
  ARCH="$1"
  KSU="$2"
  MODPATH=/tmp/cts-share-installer-test
  export ARCH KSU MODPATH
  permissions_set=false
  ui_print() { printf '%s\n' "$*"; }
  abort() { printf '%s\n' "$*" >&2; exit 42; }
  set_perm_recursive() {
    [ "$*" = "$MODPATH 0 0 0755 0644" ] || exit 43
    permissions_set=true
  }
  . "$script"
  [ "$permissions_set" = true ] || exit 44
  printf 'CTS_INSTALL_COMPLETED\n'
)

run_supported_install() {
  result=$(run_install arm64 "$1") || return "$?"
  printf '%s\n' "$result"
  case "$result" in
    *CTS_INSTALL_COMPLETED) ;;
    *) printf 'FAIL: installer exited before completion\n'; exit 1 ;;
  esac
}

run_supported_install true
run_supported_install ''
status=0
run_install arm true || status=$?
[ "$status" -eq 42 ] || { printf 'FAIL: unsupported ABI returned %s\n' "$status"; exit 1; }
printf 'PASS: KernelSU/Magisk arm64 installer flow; unsupported ABI rejected\n'
