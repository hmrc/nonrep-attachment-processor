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

//TODO test the below in a unit tests for queue

  lazy val sqsTopicArn: String = sys.env.getOrElse("ATTACHMENT_SQS", throw new RuntimeException(s"could not find value for ATTACHMENT_SQS in ${sys.env.mkString(",")}"))
  private val systemParams = config.getObject(s"$appName.system-params").toConfig
  val maxBufferSize = systemParams.getInt("maxBufferSize")
  val waitTime = systemParams.getInt("waitTime")
  val maxBatchSize = systemParams.getInt("maxBatchSize")



  private val configFile = new java.io.File(s"/etc/config/CONFIG_FILE")

  val config: Config =
    if (configFile.exists()) ConfigFactory.parseFile(configFile)
    else ConfigFactory.load("application.conf")

  override def toString =
    s"""
    appName: $appName
    port: $servicePort
    env: $env
    sqsTopicArn: $sqsTopicArn
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""

}