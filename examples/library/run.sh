#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mode="${1:-test}"
shift || true
mkdir -p build/library-classes
h2="build/lib/h2-2.2.224.jar"
if [[ ! -f "$h2" ]]; then
  echo "Download H2 first; see examples/library/README.md" >&2
  exit 1
fi
find examples/library/src -name '*.java' -print > build/library-sources.txt
javac --release 17 -encoding UTF-8 -d build/library-classes @build/library-sources.txt
case "$mode" in
  test) java -cp "build/library-classes:$h2" io.github.codeideaai.library.AcceptanceTest ;;
  serve) java -cp "build/library-classes:$h2" io.github.codeideaai.library.LibraryApp "$@" ;;
  *) echo "Usage: bash examples/library/run.sh [test|serve [port [jdbc-url]]]" >&2; exit 2 ;;
esac
