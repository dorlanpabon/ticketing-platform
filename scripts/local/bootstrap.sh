#!/bin/sh
set -eu
attempt=0
until aws dynamodb list-tables --endpoint-url "$DYNAMODB_ENDPOINT" >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] || exit 1
  sleep 2
done
for service in catalog orders inventory; do
  table="ticketing-local-$service"
  if ! aws dynamodb describe-table --table-name "$table" --endpoint-url "$DYNAMODB_ENDPOINT" >/dev/null 2>&1; then
    aws dynamodb create-table --table-name "$table" --endpoint-url "$DYNAMODB_ENDPOINT" \
      --billing-mode PAY_PER_REQUEST \
      --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S AttributeName=gsi1pk,AttributeType=S AttributeName=gsi1sk,AttributeType=S AttributeName=gsi2pk,AttributeType=S AttributeName=gsi2sk,AttributeType=S \
      --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
      --global-secondary-indexes file:///bootstrap/indexes.json >/dev/null
    aws dynamodb update-time-to-live --table-name "$table" --endpoint-url "$DYNAMODB_ENDPOINT" --time-to-live-specification Enabled=true,AttributeName=ttl >/dev/null
  fi
done
echo 'Local DynamoDB tables are ready.'
