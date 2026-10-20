#!/bin/sh
set -eu
library_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$library_root"
exec sh ./mvnw install "$@"
