#!/bin/bash
set -eu
(set -o pipefail) 2>/dev/null && set -o pipefail

if [ ! -d "target/dependency" ]; then
	mvn -DskipTests dependency:copy-dependencies -DoutputDirectory=target/dependency
fi

cp=".:target/classes:target/dependency/*"
java -Xmx2048m -Dwzpath=wz -cp "$cp" net.server.Server
