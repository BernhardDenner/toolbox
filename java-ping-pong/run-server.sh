#!/bin/sh

set -e

javac PingPong.java

exec java -Xmx4G -XX:InitiatingHeapOccupancyPercent=90 PingPong server
