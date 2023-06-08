#!/bin/bash

img="bernhard97/webservice:0.1.2"

docker build -t ${img} .
docker push ${img}
