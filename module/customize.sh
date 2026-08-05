#!/system/bin/sh

ui_print "- Installing CTS Selection Share (Zygisk)"
ui_print "- Target: arm64-v8a, Google App :googleapp process"

if [ "$ARCH" != "arm64" ]; then
  abort "! This module currently supports arm64 devices only"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
