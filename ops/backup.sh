#!/usr/bin/env bash
# Daily backup of the Lezitech Suite database on the VPS.
# Install (as root on the VPS):
#   cp ops/backup.sh /usr/local/bin/suite-backup && chmod 700 /usr/local/bin/suite-backup
#   crontab -e   ->   15 2 * * * /usr/local/bin/suite-backup >> /var/log/suite-backup.log 2>&1
# Settings (put them in /etc/suite-backup.env, chmod 600):
#   PG_CONTAINER   name of the Postgres container in Coolify (docker ps | grep postgres)
#   PG_USER        database owner (the Flyway user), PG_DB database name (suite)
#   BACKUP_DIR     where dumps go (default /var/backups/suite), KEEP_DAYS (default 14)
#   RCLONE_REMOTE  optional, e.g. "b2:lezitech-backups" to copy each dump off the server
set -euo pipefail
[ -f /etc/suite-backup.env ] && . /etc/suite-backup.env
: "${PG_CONTAINER:?set PG_CONTAINER}" "${PG_USER:?set PG_USER}"
PG_DB="${PG_DB:-suite}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/suite}"
KEEP_DAYS="${KEEP_DAYS:-14}"
mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y-%m-%d_%H%M)"
FILE="$BACKUP_DIR/suite_${STAMP}.dump"

# Custom format: compressed, and pg_restore can restore one table if needed.
docker exec "$PG_CONTAINER" pg_dump -U "$PG_USER" -d "$PG_DB" -Fc --no-owner > "$FILE.tmp"
mv "$FILE.tmp" "$FILE"
# A dump that pg_restore can't list is not a backup.
docker exec -i "$PG_CONTAINER" pg_restore --list < "$FILE" > /dev/null
echo "$(date -Is) backup ok: $FILE ($(du -h "$FILE" | cut -f1))"

if [ -n "${RCLONE_REMOTE:-}" ]; then
  rclone copy "$FILE" "$RCLONE_REMOTE/" && echo "$(date -Is) copied to $RCLONE_REMOTE"
fi
find "$BACKUP_DIR" -name 'suite_*.dump' -mtime +"$KEEP_DAYS" -delete
