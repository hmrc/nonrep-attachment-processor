#!/usr/bin/env bash

for i in {1..20}
do
awslocal sqs send-message --queue-url http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/local-nonrep-attachment-queue --message-body '{
  "Records": [
    {
      "eventVersion": "2.0",
      "eventSource": "aws:s3",
      "awsRegion": "eu-west-2",
      "eventTime": "2018-07-17T14:08:56.784Z",
      "eventName": "ObjectCreated:Put",
      "userIdentity": {
        "principalId": "AWS:AROAI6UKNMK6GNG3RQ4J6:adam-put2_p"
      },
      "requestParameters": {
        "sourceIPAddress": "35.178.67.252"
      },
      "responseElements": {
        "x-amz-request-id": "AEACEBA7C61C2BCE",
        "x-amz-id-2": "KKUq2q4T+66NOwEqvAZxAH7HefNI/KdVVbVZxf0/qS8V4n4nmlINLkg86n2shIvvsGgjHGnAGTA="
      },
      "s3": {
        "s3SchemaVersion": "1.0",
        "configurationId": "sns1",
        "bucket": {
          "name": "local-nonrep-attachment-data",
          "ownerIdentity": {
            "principalId": "A202PFQUTJVUOI"
          },
          "arn": "arn:aws:s3:::adam1-nonrep-submission-data"
        },
        "object": {
          "key": "738bcba6-7f9e-11ec-8768-3f8498104f38.zip",
          "size": 10000,
          "eTag": "93579cc5c9c8246e7ad30f14b99ecb83",
          "sequencer": "005B4DF878BDCFA069"
        }
      }
    }
  ]
}'
done