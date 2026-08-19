#!/usr/bin/env bash
# Dependency-free build + conformance run for the ATP Java Producer SDK.
# No Maven/Gradle/network required — just a JDK >= 17 (records + platform Ed25519).
#
# Usage: ./build.sh            # compile main + tests, run the conformance suite
#        ./build.sh validate   # additionally run the PDD bundle validator (needs python3)
set -euo pipefail

cd "$(dirname "$0")"

# Locate a JDK >= 17. Prefer JAVA_HOME, then macOS java_home, then PATH javac.
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/javac" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"; JAVA="$JAVA_HOME/bin/java"
elif command -v /usr/libexec/java_home >/dev/null 2>&1 && /usr/libexec/java_home -v 17 >/dev/null 2>&1; then
  JH="$(/usr/libexec/java_home -v 17)"; JAVAC="$JH/bin/javac"; JAVA="$JH/bin/java"
else
  JAVAC="$(command -v javac)"; JAVA="$(command -v java)"
fi
echo "Using: $($JAVAC -version 2>&1)"

MAIN_OUT=build/main
TEST_OUT=build/test
rm -rf build
mkdir -p "$MAIN_OUT" "$TEST_OUT"

echo "==> Compiling main sources (--release 17)"
find src/main/java -name '*.java' > build/main.srcs
"$JAVAC" --release 17 -Xlint:all -Werror -d "$MAIN_OUT" @build/main.srcs

echo "==> Compiling test sources"
find src/test/java -name '*.java' > build/test.srcs
"$JAVAC" --release 17 -cp "$MAIN_OUT" -d "$TEST_OUT" @build/test.srcs

echo "==> Running conformance suite"
"$JAVA" -cp "$MAIN_OUT:$TEST_OUT" io.openkedge.atp.TestMain

if [[ "${1:-}" == "validate" ]]; then
  echo "==> Validating PDD bundle"
  python3 "${PDD_VALIDATOR:-/tmp/pdd-protocol-author/scripts/validate_pdd_bundle.py}" \
    pdd-bundles/atp-java-producer
fi

echo "==> OK"
