#!/usr/bin/env bash
# Precipitation launcher: build if needed, then open the game (main menu).
# The viewer starts the Python simulation itself and stops it when you leave or quit.
# Extra arguments are passed through, e.g.  ./run.sh --fake   or   ./run.sh --new
set -e
cd "$(dirname "$0")"
# Rebuild when there is no finished build, or when any source file (new ones included) is newer than it.
if [ ! -f viewer/out/precip/Main.class ] || [ ! -f viewer/out/build.stamp ] \
   || [ -n "$(find viewer/src/precip -name '*.java' -newer viewer/out/build.stamp)" ]; then
  ./build.sh
fi
exec java -cp viewer/out precip.Main "$@"
