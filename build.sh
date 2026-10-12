#!/usr/bin/env bash
# Build the Java viewer (plain javac, no dependencies).
# On success writes viewer/out/build.stamp (run.sh compares the sources against it).
set -e
cd "$(dirname "$0")"
mkdir -p viewer/out
rm -f viewer/out/build.stamp
javac -encoding UTF-8 -d viewer/out viewer/src/precip/*.java
date > viewer/out/build.stamp
echo "Build OK: viewer/out"
