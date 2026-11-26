#!/bin/sh
# Minimal Gradle wrapper launcher. Needs gradle/wrapper/gradle-wrapper.jar (the CI workflow generates it
# if it is missing). `gradle wrapper --gradle-version 8.9` replaces this script with the official one.
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
