#!/bin/sh
# Reload udev so 70-ridley-spacemouse.rules applies without a reboot/replug.
udevadm control --reload-rules && udevadm trigger --subsystem-match=hidraw || true
