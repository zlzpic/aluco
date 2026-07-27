#!/usr/bin/env bash
# tear down the stack; add -v to also wipe MySQL data
cd "$(dirname "$0")"
docker compose down "$@"