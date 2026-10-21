#!/bin/sh
set -eu

# Started as root (the default): make the data directory, which Docker may have created root-owned for
# a bind mount, writable for PUID:PGID and drop to that user. Started with --user: run as given.
if [ "$(id -u)" = "0" ]; then
    PUID="${PUID:-1000}"
    PGID="${PGID:-1000}"
    mkdir -p "$HEIM_DATA_DIR"
    chown -R "$PUID:$PGID" "$HEIM_DATA_DIR"
    exec su-exec "$PUID:$PGID" java -XX:MaxRAMPercentage=75 -jar /app/heim.jar "$@"
fi

exec java -XX:MaxRAMPercentage=75 -jar /app/heim.jar "$@"
