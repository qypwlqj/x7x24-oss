#!/bin/sh
set -eu

# Next serves the public port; the API listens only inside the container.
java ${JAVA_OPTS:-} -jar /app/app.jar --server.address=127.0.0.1 --server.port=8788 &
api_pid=$!
node /app/apps/web/server.js &
web_pid=$!
stop() { kill -TERM "$api_pid" "$web_pid" 2>/dev/null || true; }
trap 'exit 0' INT TERM
trap stop EXIT
while kill -0 "$api_pid" 2>/dev/null && kill -0 "$web_pid" 2>/dev/null; do sleep 1; done
stop
wait "$api_pid" 2>/dev/null || true
wait "$web_pid" 2>/dev/null || true
exit 1
