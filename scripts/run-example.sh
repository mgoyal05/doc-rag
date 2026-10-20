#!/bin/sh
set -eu
library_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$library_root"
if [ -f .env ]; then set -a; . ./.env; set +a; fi
test -s examples/spring-boot/target/doc-rag-example.jar || { echo 'Build first with sh scripts/build.sh' >&2; exit 1; }
exec java -jar examples/spring-boot/target/doc-rag-example.jar "$@"
