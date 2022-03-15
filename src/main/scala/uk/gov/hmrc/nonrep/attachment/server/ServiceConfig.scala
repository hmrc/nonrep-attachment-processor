package uk.gov.hmrc.nonrep.attachment
package server

import java.net.URI

import com.typesafe.config.{Config, ConfigFactory}

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

  val queueUrl: String = if (env == "local") "local" else sqsSystemProperty

  private [server] def sqsSystemProperty: String =
    sys.env.getOrElse(
      "ATTACHMENT_SQS",
      throw new IllegalStateException(
        "System property SQS queue url connection not set. This is required by the service to create a SQS queue message when necessary."))

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
  val maxBufferSize: Int = systemParams.getInt("maxBufferSize")
  val maxBatchSize: Int = systemParams.getInt("maxBatchSize")
  val closeOnEmptyReceive: Boolean = systemParams.getBoolean("closeOnEmptyReceive")
  val waitTimeSeconds: Int = systemParams.getInt("waitTimeSeconds")


  override def toString =
    s"""
    appName: $appName
    port: $servicePort
    env: $env
    queueUrl: $queueUrl
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""

}