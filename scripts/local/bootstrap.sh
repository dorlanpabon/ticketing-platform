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
attempt=0
until aws sqs list-queues --endpoint-url "$SQS_ENDPOINT" >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  [ "$attempt" -lt 30 ] || exit 1
  sleep 2
done
# Add the dedicated timer queue when reusing an existing emulator volume.
for queue in inventory-expirations-dlq inventory-expirations; do
  if ! aws sqs get-queue-url --queue-name "$queue" --endpoint-url "$SQS_ENDPOINT" >/dev/null 2>&1; then
    aws sqs create-queue --queue-name "$queue" --endpoint-url "$SQS_ENDPOINT" >/dev/null
  fi
done
expiration_dlq_url=$(aws sqs get-queue-url --queue-name inventory-expirations-dlq --endpoint-url "$SQS_ENDPOINT" --query QueueUrl --output text)
expiration_queue_url=$(aws sqs get-queue-url --queue-name inventory-expirations --endpoint-url "$SQS_ENDPOINT" --query QueueUrl --output text)
expiration_dlq_arn=$(aws sqs get-queue-attributes --queue-url "$expiration_dlq_url" --attribute-names QueueArn --endpoint-url "$SQS_ENDPOINT" --query Attributes.QueueArn --output text)
aws sqs set-queue-attributes --queue-url "$expiration_dlq_url" --endpoint-url "$SQS_ENDPOINT" --attributes VisibilityTimeout=60,MessageRetentionPeriod=1209600 >/dev/null
printf '{"VisibilityTimeout":"60","ReceiveMessageWaitTimeSeconds":"20","MessageRetentionPeriod":"345600","RedrivePolicy":"{\\"deadLetterTargetArn\\":\\"%s\\",\\"maxReceiveCount\\":\\"5\\"}"}\n' "$expiration_dlq_arn" >/tmp/expiration-attributes.json
aws sqs set-queue-attributes --queue-url "$expiration_queue_url" --endpoint-url "$SQS_ENDPOINT" --attributes file:///tmp/expiration-attributes.json >/dev/null
echo 'Local tables and queues are ready; no business fixtures were changed.'
