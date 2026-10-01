#!/bin/sh
set -eu
endpoint=http://dynamodb:8000
attempt=0
until aws dynamodb list-tables --endpoint-url "$endpoint" >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 30 ]; then echo "DynamoDB failed to start" >&2; exit 1; fi
  sleep 2
done
if ! aws dynamodb describe-table --table-name franchises --endpoint-url "$endpoint" >/dev/null 2>&1; then
  aws dynamodb create-table --table-name franchises --billing-mode PAY_PER_REQUEST \
    --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
    --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE --endpoint-url "$endpoint"
fi
aws dynamodb wait table-exists --table-name franchises --endpoint-url "$endpoint"
