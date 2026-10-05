#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/classes
javac --release 17 -d build/classes examples/*.java
for lab in IocLab CycleLab AopLab MvcLab JdbcLab; do
  java -cp build/classes "$lab"
done
