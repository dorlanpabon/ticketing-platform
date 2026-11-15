#!/bin/sh
set -eu

if [ "${1:-}" = health ]; then
  last_success=$(cat /tmp/recovery-last-success 2>/dev/null || printf '0')
  now=$(date +%s)
  [ "$((now - last_success))" -lt 180 ]
  exit
fi

while true; do
  message_id=$(cat /proc/sys/kernel/random/uuid)
  occurred_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  printf '{"messageId":"%s","type":"SweepExpiredReservations","schemaVersion":1,"aggregateId":"inventory-maintenance","correlationId":"%s","causationId":"%s","occurredAt":"%s","payload":{}}\n' \
    "$message_id" "$message_id" "$message_id" "$occurred_at" >/tmp/recovery-envelope.json
  until aws sqs send-message --endpoint-url "$SQS_ENDPOINT" --queue-url "$INVENTORY_EXPIRATIONS_QUEUE_URL" \
      --message-body file:///tmp/recovery-envelope.json --cli-connect-timeout 2 --cli-read-timeout 5 >/dev/null 2>&1; do
    printf '{"service":"local-expiration-recovery","status":"retry","messageId":"%s"}\n' "$message_id"
    sleep 5
  done
  date +%s >/tmp/recovery-last-success
  printf '{"service":"local-expiration-recovery","status":"sent","messageId":"%s"}\n' "$message_id"
  sleep 60
done
