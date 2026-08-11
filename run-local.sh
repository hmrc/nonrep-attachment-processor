#!/usr/bin/env bash

# To enable execution with nonrep-stubs

export AWS_ENDPOINT_URL=http://localhost:4566
export AWS_ACCESS_KEY_ID=fu
export AWS_SECRET_ACCESS_KEY=bar
export GLACIER_SNS="local"
export ATTACHMENT_SQS=http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/local-nonrep-attachment-queue
export ELASTICSEARCH=http://nonrep.eu-west-2.es.localhost.localstack.cloud:4566


ENV=local sbt  'set javaOptions ++= Seq("-Dcom.sun.management.jmxremote.port=8080", "-Dcom.sun.management.jmxremote.ssl=false", "-Dcom.sun.management.jmxremote.authenticate=false",   "-Xmx1G", "-Dconfig.resource=local.conf", "-Dlogback.configurationFile=logback-local.xml"); run'
