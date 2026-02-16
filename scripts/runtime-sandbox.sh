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
PROJECT_CP_FILE="${TMPDIR:-/tmp}/camelot-runtime-sandbox-${USER:-user}-$$.project.cp"

if [ ! -d "$PROJECT_DIR" ]; then
  echo "Project directory not found: $PROJECT_DIR"
  exit 4
fi

cleanup() {
  rm -f "$CP_FILE"
  rm -f "$JAR_LIST_FILE"
  rm -f "$PROJECT_CP_FILE"
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

PROJECT_EXTRA_CP=""
if [ -f "$PROJECT_DIR/pom.xml" ]; then
  if mvn -q -f "$PROJECT_DIR/pom.xml" -Dmaven.repo.local="$REPO_DIR/.m2repo" -Dmdep.outputFile="$PROJECT_CP_FILE" -DincludeScope=runtime dependency:build-classpath >/dev/null 2>&1; then
    PROJECT_EXTRA_CP="$(cat "$PROJECT_CP_FILE")"
  elif mvn -q -f "$PROJECT_DIR/pom.xml" -Dmdep.outputFile="$PROJECT_CP_FILE" -DincludeScope=runtime dependency:build-classpath >/dev/null 2>&1; then
    PROJECT_EXTRA_CP="$(cat "$PROJECT_CP_FILE")"
  else
    echo "[runtime-sandbox] warn: cannot resolve target project runtime classpath from pom.xml, continue with auto-discovered classes/jars." >&2
  fi
fi

SIM_ARGS=(
  --project "$PROJECT_DIR"
  --entry-method "$ENTRY_METHOD"
)

if [ -n "$PROJECT_EXTRA_CP" ]; then
  SIM_ARGS+=(--classpath "$PROJECT_EXTRA_CP")
fi

if [ "$#" -gt 0 ]; then
  SIM_ARGS+=("$@")
fi

java -javaagent:"$AGENT_JAR" -cp "$JAVA_CP" \
  com.camelot.analyzer.RuntimeSandboxSimulator \
  "${SIM_ARGS[@]}"
