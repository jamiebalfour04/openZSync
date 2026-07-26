#!/bin/sh
set -eu

PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
BUILD_DIR="$PROJECT_DIR/build"
CLASS_DIR="$BUILD_DIR/classes"
TEST_CLASS_DIR="$BUILD_DIR/test-classes"

rm -rf "$BUILD_DIR"
mkdir -p "$CLASS_DIR" "$TEST_CLASS_DIR"

javac --release 11 \
  -d "$CLASS_DIR" \
  $(find "$PROJECT_DIR/src/main/java" -name '*.java' -print)

javac --release 11 \
  -cp "$CLASS_DIR" \
  -d "$TEST_CLASS_DIR" \
  $(find "$PROJECT_DIR/src/test/java" -name '*.java' -print)

jar --create \
  --file "$BUILD_DIR/zsync.jar" \
  --main-class jamiebalfour.zsync.Main \
  -C "$CLASS_DIR" .

java -cp "$CLASS_DIR:$TEST_CLASS_DIR" jamiebalfour.zsync.DirectorySynchroniserTest
echo "Built $BUILD_DIR/zsync.jar"
