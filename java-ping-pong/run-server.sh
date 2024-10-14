#!/bin/sh

set -e

javac PingPong.java

: ${JAVA_HEAP_SIZE:=4G}

echo "using JAVA_HEAP_SIZE=${JAVA_HEAP_SIZE}"

exec java -Xmx${JAVA_HEAP_SIZE} -XX:InitiatingHeapOccupancyPercent=90 PingPong server "$@"
