package uk.gov.hmrc.nonrep.attachment
package server

import java.net.URI

import com.typesafe.config.{Config, ConfigFactory}

class ServiceConfig(val servicePort: Int = 8000) {

  val appName = "attachment-processor"
  val port: Int = sys.env.get("REST_PORT").fold(servicePort)(_.toInt)
  val env: String = sys.env.getOrElse("ENV", "local")

  def isSandbox: Boolean = !Set("dev", "qa", "staging", "production").contains(env)

  val queueUrl: String = if (env == "local") "local" else sqsSystemProperty

  private [server] def sqsSystemProperty: String =
    sys.env.getOrElse(
      "ATTACHMENT_SQS",
      throw new IllegalStateException(
        "System property SQS queue url connection not set. This is required by the service to create a SQS queue message when necessary."))

  val attachmentsBucket = s"$env-nonrep-attachment-data"

  val elasticSearchUri: URI = URI.create(sys.env.getOrElse("ELASTICSEARCH", "http://elasticsearch.nrs"))
  val isElasticSearchProtocolSecure: Boolean = elasticSearchUri.toURL.getProtocol == "https"
  val elasticSearchHost: String = elasticSearchUri.getHost

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
  val messagesPerSecond: Int = systemParams.getInt("messagesPerSecond")

  val signServiceBufferSize: Int = systemParams.getInt("signServiceBufferSize")
  val esServiceBufferSize: Int = systemParams.getInt("esServiceBufferSize")
  
  override def toString =
    s"""
    appName: $appName
    port: $servicePort
    env: $env
    queueUrl: $queueUrl
    elasticSearchUri: $elasticSearchUri
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""

}