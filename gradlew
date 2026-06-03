#!/bin/sh
# Gradle wrapper script
GRADLE_OPTS="${GRADLE_OPTS:-""}"
APP_HOME="$(cd "$(dirname "$0")" && pwd -P)"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec java $GRADLE_OPTS -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
