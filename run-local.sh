#!/usr/bin/env bash

export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_ACCESS_KEY_ID=fu
export AWS_SECRET_ACCESS_KEY=bar
export GLACIER_SNS="local"
export SIGN_SQS=http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/local-nonrep-attachment-queue
export ELASTICSEARCH=http://nonrep.eu-west-2.es.localhost.localstack.cloud:4566

ENV=local sbt  'set javaOptions ++= Seq("-Dconfig.resource=local.conf", "-Xmx1G", "-Dlogback.configurationFile=logback-console.xml"); run'
