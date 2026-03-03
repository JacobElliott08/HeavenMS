#!/bin/bash
# compilation script for posix-compliant systems
set -eu
(set -o pipefail) 2>/dev/null && set -o pipefail

mvn -DskipTests package
mvn -DskipTests dependency:copy-dependencies -DoutputDirectory=target/dependency
