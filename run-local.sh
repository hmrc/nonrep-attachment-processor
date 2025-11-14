#!/usr/bin/env bash

# To enable execution with nonrep-stubs

ENV=local sbt  'set javaOptions ++= Seq("-Dconfig.resource=local.conf", "-Dlogback.configurationFile=logback-console.xml"); run'
