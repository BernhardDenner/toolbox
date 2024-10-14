#!/bin/sh

set -e

javac PingPong.java

exec java PingPong client "$@"
