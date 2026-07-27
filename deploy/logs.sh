#!/usr/bin/env bash
# tail logs of the whole stack or one service: ./logs.sh server
cd "$(dirname "$0")"
docker compose logs -f --tail=200 "$@"