#!/usr/bin/env bash

set -euo pipefail

if [ "$#" -lt 2 ]; then
  echo "Usage: $0 <project-dir> <entry-method> [extra-args...]"
  echo "Example: $0 /path/to/project com.foo.UserService#findUser/1 --arg 42"
  exit 1
fi

PROJECT_DIR="$1"
ENTRY_METHOD="$2"
shift 2

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CP_FILE="${TMPDIR:-/tmp}/camelot-runtime-sandbox-${USER:-user}-$$.cp"
JAR_LIST_FILE="${TMPDIR:-/tmp}/camelot-runtime-sandbox-${USER:-user}-$$.jars"

cleanup() {
  rm -f "$CP_FILE"
  rm -f "$JAR_LIST_FILE"
}
trap cleanup EXIT

cd "$REPO_DIR"

mvn -q -Dmaven.repo.local="$REPO_DIR/.m2repo" -DskipTests compile

find "$REPO_DIR/.m2repo" -name "*.jar" -print | sort > "$JAR_LIST_FILE"
if [ ! -s "$JAR_LIST_FILE" ]; then
  echo "Cannot build classpath: no jars found under $REPO_DIR/.m2repo"
  exit 2
fi

paste -sd ':' "$JAR_LIST_FILE" > "$CP_FILE"

AGENT_JAR="$(grep -E 'byte-buddy-agent-[^/]*\.jar$' "$JAR_LIST_FILE" | head -n1 || true)"
if [ -z "$AGENT_JAR" ]; then
  echo "Cannot find byte-buddy-agent jar in Maven classpath."
  exit 3
fi

JAVA_CP="target/classes:$(cat "$CP_FILE")"

java -javaagent:"$AGENT_JAR" -cp "$JAVA_CP" \
  com.camelot.analyzer.RuntimeSandboxSimulator \
  --project "$PROJECT_DIR" \
  --entry-method "$ENTRY_METHOD" \
  "$@"
