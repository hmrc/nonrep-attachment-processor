package uk.gov.hmrc.nonrep.attachment
package server

import java.net.URI
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.stream.connectors.s3.S3Settings

class ServiceConfig(val servicePort: Int = 8000) {

  val appName     = "attachment-processor"
  val port: Int   = sys.env.get("REST_PORT").fold(servicePort)(_.toInt)
  val env: String = sys.env.getOrElse("ENV", "local")

  def isSandbox: Boolean = !Set("dev", "qa", "staging", "production").contains(env)

  val queueUrl: String = if env == "local" then "http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/local-nonrep-attachment-queue" else sqsSystemProperty
  private[server] def sqsSystemProperty: String =
    sys.env.getOrElse(
      "ATTACHMENT_SQS",
      throw new IllegalStateException(
        "System property SQS queue url connection not set. This is required by the service to create a SQS queue message when necessary."
      )
    )

  val attachmentsBucket: String = s"$env-nonrep-attachment-data"

  val elasticSearchUri: URI                  = URI.create(sys.env.getOrElse("ELASTICSEARCH", "http://elasticsearch.nrs"))
  val isElasticSearchProtocolSecure: Boolean = elasticSearchUri.toURL.getProtocol == "https"
  val elasticSearchHost: String              = elasticSearchUri.getHost

  private val configFile = new java.io.File(s"/etc/config/CONFIG_FILE")

  val config: Config =
    if configFile.exists() then ConfigFactory.parseFile(configFile)
    else ConfigFactory.load("application.conf")

  val awsSettings: S3Settings = S3Settings(config.getConfig(S3Settings.ConfigPath))

  val refreshPolicy: String = config.getConfig("metastore").getString("refresh_policy")

  private val signaturesParams           = config.getObject(s"$appName.signatures").toConfig
  val signaturesServiceUri: URI = URI.create(signaturesParams.getString("service-url"))
  val isSignaturesServiceSecure: Boolean = false
  val signaturesServiceHost: String      = "localhost"
  val signaturesServicePort: Int         = 8999
  val signingProfile: String             = signaturesParams.getString("signing-profile")

  private val systemParams         = config.getObject(s"$appName.system-params").toConfig
  val maxBufferSize: Int           = systemParams.getInt("maxBufferSize")
  val maxBatchSize: Int            = systemParams.getInt("maxBatchSize")
  val closeOnEmptyReceive: Boolean = systemParams.getBoolean("closeOnEmptyReceive")
  val waitTimeSeconds: Int         = systemParams.getInt("waitTimeSeconds")
  val messagesPerSecond: Int       = systemParams.getInt("messagesPerSecond")

  val signServiceBufferSize: Int = systemParams.getInt("signServiceBufferSize")
  val esServiceBufferSize: Int   = systemParams.getInt("esServiceBufferSize")

  override def toString: String =
    s"""
    appName: $appName
    port: $servicePort
    env: $env
    queueUrl: $queueUrl
    elasticSearchUri: $elasticSearchUri
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""
}
