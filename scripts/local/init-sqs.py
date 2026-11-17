"""Provision the local SQS topology when LocalStack is ready."""

import json

import boto3


QUEUE_NAMES = ('inventory-commands', 'inventory-expirations', 'order-events', 'catalog-events')
DLQ_ATTRIBUTES = {'VisibilityTimeout': '60', 'MessageRetentionPeriod': '1209600'}
QUEUE_ATTRIBUTES = {
    'VisibilityTimeout': '60',
    'ReceiveMessageWaitTimeSeconds': '20',
    'MessageRetentionPeriod': '345600',
}
MAX_RECEIVE_COUNT = 5

sqs = boto3.client('sqs', endpoint_url='http://127.0.0.1:4566')
for name in QUEUE_NAMES:
    dlq_url = sqs.create_queue(QueueName=f'{name}-dlq', Attributes=DLQ_ATTRIBUTES)['QueueUrl']
    attributes = sqs.get_queue_attributes(QueueUrl=dlq_url, AttributeNames=['QueueArn'])['Attributes']
    redrive_policy = json.dumps({
        'deadLetterTargetArn': attributes['QueueArn'],
        'maxReceiveCount': MAX_RECEIVE_COUNT,
    })
    sqs.create_queue(QueueName=name, Attributes={**QUEUE_ATTRIBUTES, 'RedrivePolicy': redrive_policy})
