#!/bin/sh
#
# Gradle startup script for UN*X
#
APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`
APP_HOME=`dirname "$0"`
APP_HOME=`cd "$APP_HOME" && pwd`

GRADLE_OPTS="$GRADLE_OPTS \"-Xdock:name=$APP_NAME\" \"-Xdock:icon=$APP_HOME/media/gradle.icns\""

MAX_FD="maximum"
warn () { echo "$*"; }
die () { echo; echo "$*"; echo; exit 1; }

case "`uname`" in
  Linux*) MAX_FD_LIMIT=`ulimit -H -n` ;;
  *) MAX_FD_LIMIT=`ulimit -H -n` ;;
esac

if [ "$MAX_FD" = "maximum" -o "$MAX_FD" = "max" ]; then
    MAX_FD="$MAX_FD_LIMIT"
fi
ulimit -n $MAX_FD 2>/dev/null || warn "Could not set maximum file descriptor limit: $MAX_FD"

CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

JAVACMD="java"
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
fi

exec "$JAVACMD" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
