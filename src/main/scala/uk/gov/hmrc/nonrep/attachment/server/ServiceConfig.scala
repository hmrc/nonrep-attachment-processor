package uk.gov.hmrc.nonrep.attachment
package server

import com.typesafe.config.{Config, ConfigFactory}

import java.net.URI

class ServiceConfig(val servicePort: Int = 8000) {

  val appName = "attachment-processor"
  val port: Int = sys.env.get("REST_PORT").fold(servicePort)(_.toInt)
  val env: String = sys.env.getOrElse("ENV", "local")
  val glacierNotificationsSnsTopicArn: String = if (env == "local") "local" else glacierSNSSystemProperty

  private[server] def glacierSNSSystemProperty =
      sys.env.getOrElse(
        "GLACIER_SNS",
        throw new IllegalStateException(
          "System property GLACIER_SNS is not set. This is required by the service to create a Glacier vault when necessary."))

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

  private val systemParams = config.getObject(s"$appName.system-params").toConfig
  val waitTime = systemParams.getInt("waitTime")
  val maxBufferSize = systemParams.getInt("maxBufferSize")
  val maxBatchSize = systemParams.getInt("maxBatchSize")
  val closeOnEmptyReceive = systemParams.getBoolean("false")
  val waitTimeSeconds = systemParams.getInt("waitTimeSeconds")

  val queueUrl = s"https://sqs.eu-west-2.amazonaws.com/205520045207/$env-nonrep-attachment-queue"

  override def toString =
    s"""
    appName: $appName
    port: $servicePort
    env: $env
    queueUrl: $queueUrl
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""

}