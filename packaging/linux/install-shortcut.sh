#!/usr/bin/env bash
# Adds CodeRun to the applications menu (run once after extracting). Nothing is installed system-wide.
HERE="$(cd "$(dirname "$0")" && pwd)"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
mkdir -p "$APPS"
ICON="$HERE/lib/CodeRun.png"
[ -f "$ICON" ] || ICON="$(ls "$HERE"/lib/*.png 2>/dev/null | head -1)"
cat > "$APPS/coderun.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=CodeRun 2.0
Comment=CodeRun 2.0 launcher
Exec="$HERE/bin/CodeRun"
Path=$HERE
Icon=$ICON
Terminal=false
Categories=Game;
EOF
chmod +x "$APPS/coderun.desktop"
command -v update-desktop-database >/dev/null && update-desktop-database "$APPS" >/dev/null 2>&1 || true
echo "CodeRun 2.0 added to your applications menu."
