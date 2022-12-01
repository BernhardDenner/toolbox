#!/bin/bash -ex

version=$1

[ -z "$version" ] && exit 1


docker build -t bernhard97/bunny-pub:${version} pub/
docker build -t bernhard97/bunny-sub:${version} sub/

docker push bernhard97/bunny-pub:${version}
docker push bernhard97/bunny-sub:${version}
