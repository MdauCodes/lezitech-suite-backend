#!/usr/bin/env bash
# BizNez uptime check, run every minute by cron on the server (phase C).
# Asks the API's health page; after 2 failed minutes in a row it emails LeziTech once, and once more when it's back.
# Settings in /etc/suite-uptime.env (chmod 600):
#   HEALTH_URL=https://suite-api.lezitech.site/actuator/health
#   ALERT_EMAIL=you@example.com          # who hears about it
#   BREVO_API_KEY=xkeysib-...            # the same Brevo key the API sends email with
#   FROM_EMAIL=...                       # TODO owner: set to a sender address on a domain verified in Brevo (businez.com is not live yet)
# Cron:  * * * * * /usr/local/bin/suite-uptime >> /var/log/suite-uptime.log 2>&1
set -u
source /etc/suite-uptime.env
STATE=/var/lib/suite-uptime
mkdir -p "$STATE"
fails=$(cat "$STATE/fails" 2>/dev/null || echo 0)
down=$(cat "$STATE/down" 2>/dev/null || echo 0)

send() {
  curl -s -m 20 -X POST https://api.brevo.com/v3/smtp/email \
    -H "api-key: $BREVO_API_KEY" -H "content-type: application/json" \
    -d "{\"sender\":{\"email\":\"$FROM_EMAIL\",\"name\":\"BizNez uptime\"},\"to\":[{\"email\":\"$ALERT_EMAIL\"}],\"subject\":\"$1\",\"textContent\":\"$2\"}" >/dev/null
}

if curl -fsS -m 10 "$HEALTH_URL" | grep -q '"status":"UP"'; then
  if [ "$down" = "1" ]; then
    send "BizNez is back up" "The API answered again at $(date -u '+%Y-%m-%d %H:%M UTC') after $fails failed checks."
    echo "$(date -u +%FT%TZ) back up after $fails"
  fi
  echo 0 > "$STATE/fails"; echo 0 > "$STATE/down"
else
  fails=$((fails + 1)); echo "$fails" > "$STATE/fails"
  echo "$(date -u +%FT%TZ) check failed ($fails)"
  if [ "$fails" -ge 2 ] && [ "$down" = "0" ]; then
    send "BizNez is DOWN" "The API health check at $HEALTH_URL has failed $fails minutes in a row (since about $(date -u -d "-$fails min" '+%H:%M UTC')). Check Coolify and the server (ssh geems-vps)."
    echo 1 > "$STATE/down"
  fi
fi
