package uk.gov.hmrc.nonrep.attachment
package server

import akka.stream.alpakka.sqs.{SqsPublishBatchSettings, SqsSourceSettings}
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration.DurationInt

class ServiceConfig(val servicePort: Int = 8000) {

  val appName = "attachment-processor"
  val port: Int = sys.env.get("REST_PORT").map(_.toInt).getOrElse(servicePort)
  val env: String = sys.env.get("ENV").getOrElse("local")

  val attachmentsBucket = s"$env-nonrep-attachment-data"

  val settings = SqsSourceSettings()
    .withWaitTime(20.seconds)
    .withMaxBufferSize(100)
    .withMaxBatchSize(10)
    .withCloseOnEmptyReceive(false)
    .withVisibilityTimeout(10.seconds)

  val batchSettings =
    SqsPublishBatchSettings()
      .withConcurrentRequests(1)

  private val configFile = new java.io.File(s"/etc/config/CONFIG_FILE")

  val config: Config =
    if (configFile.exists()) ConfigFactory.parseFile(configFile)
    else ConfigFactory.load("application.conf")

  override def toString =
    s"""
    appName: $appName
    port: $servicePort
    env: $env
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""

}