#!/bin/bash
# Convenience launcher for the Scout bridge. Prints the pairing secret on start.
#
# Uses /usr/bin/python3 (system) deliberately: the macOS application firewall
# keeps per-binary rules, and conda/uv pythons are often blocked for incoming
# connections — which resets LAN connections from the phone while loopback
# still works (exactly how it failed on 2026-06-12). The system python is
# allow-listed on this Mac and the bridge is stdlib-only (3.9+).
PYTHON=/usr/bin/python3
[ -x "$PYTHON" ] || PYTHON=python3
# -u: unbuffered stdout so the banner + pairing QR appear even when the
# bridge runs detached with output redirected to a log file.
exec "$PYTHON" -u "$(dirname "$0")/scout_bridge.py" "$@"
