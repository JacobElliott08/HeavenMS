#!/bin/sh
set -e

if [ ! -d "target/dependency" ]; then
	mvn -DskipTests dependency:copy-dependencies -DoutputDirectory=target/dependency
fi

export CLASSPATH=".:target/classes:target/dependency/*"
java -Xmx2048m -Dwzpath=wz/ net.server.Server