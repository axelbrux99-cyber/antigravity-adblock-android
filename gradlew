#!/bin/sh
# Gradle wrapper script for Unix/Linux/macOS (required by GitHub Actions)
# Standard Gradle wrapper — do not edit manually

APP_NAME="Gradle"
DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

set -e

if [ -z "$JAVA_HOME" ]; then
  JAVA_CMD="java"
else
  JAVA_CMD="$JAVA_HOME/bin/java"
fi

exec "$JAVA_CMD" $DEFAULT_JVM_OPTS \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
