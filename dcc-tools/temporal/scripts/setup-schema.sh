#!/bin/sh
# Creates Temporal's two PostgreSQL databases and their tables:
#   temporal             - workflow histories, timers, task queues (the "persistence" store)
#   temporal_visibility  - the searchable list of workflows the UI shows
set -eu

TOOL="temporal-sql-tool --plugin postgres12 --ep ${POSTGRES_SEEDS} -u ${POSTGRES_USER} -p ${DB_PORT}"
SCHEMA=/etc/temporal/schema/postgresql/v12

for db in temporal temporal_visibility; do
  dir=$([ "$db" = temporal ] && echo temporal || echo visibility)
  echo "Setting up database $db"
  $TOOL --db "$db" create
  $TOOL --db "$db" setup-schema -v 0.0
  $TOOL --db "$db" update-schema -d "$SCHEMA/$dir/versioned"
done
echo "Schema setup complete"
