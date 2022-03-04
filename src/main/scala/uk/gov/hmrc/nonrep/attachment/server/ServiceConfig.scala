package uk.gov.hmrc.nonrep.attachment
package server

import java.net.URI

import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters._

class ServiceConfig(val servicePort: Int = 8000) {

  val appName = "attachment-processor"
  val port: Int = sys.env.get("REST_PORT").map(_.toInt).getOrElse(servicePort)
  val env: String = sys.env.get("ENV").getOrElse("local")

  val attachmentsBucket = s"$env-nonrep-attachment-data"

  private val configFile = new java.io.File(s"/etc/config/CONFIG_FILE")

  val config: Config =
    if (configFile.exists()) ConfigFactory.parseFile(configFile)
    else ConfigFactory.load("application.conf")

  private val signaturesParams = config.getObject(s"$appName.signatures").toConfig
  private val signaturesServiceUri = URI.create(signaturesParams.getString("service-url"))
  val isSignaturesServiceSecure: Boolean = signaturesServiceUri.toURL.getProtocol == "https"
  val signaturesServiceHost: String = signaturesServiceUri.getHost
  val signaturesServicePort: Int = signaturesServiceUri.getPort
  val signingProfile: String = signaturesParams.getString("signing-profile")

  lazy val sqsTopicArn: String = sys.env.getOrElse("ATTACHMENT_SQS", throw new RuntimeException(s"could not find value for ATTACHMENT_SQS in ${sys.env.mkString(",")}"))
  private val systemParams = config.getObject(s"$appName.system-params").toConfig
  val maxBufferSize = systemParams.getInt("maxBufferSize")
  val waitTime = systemParams.getInt("waitTime")
  val maxBatchSize = systemParams.getInt("maxBatchSize")
//  val sourceSettings = (maxBufferSize, waitTime, maxBatchSize)

  override def toString =
    s"""
    appName: $appName
    port: $servicePort
    env: $env
    sqsTopicArn: $sqsTopicArn
    maxBatchSize: $maxBatchSize
    waitTime: $waitTime
    maxBufferSize: $maxBufferSize
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""

}