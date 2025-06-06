#!/bin/bash
set -e # Exit immediately if a command exits with a non-zero status.

echo "--- Starting YAPPY Engine with Tika Parser using static supervisord.conf ---"

# The 'exec' command replaces the shell process with the supervisord process,
# which is a best practice for container entrypoints.
exec /usr/bin/supervisord -c /etc/supervisor/conf.d/supervisord.conf