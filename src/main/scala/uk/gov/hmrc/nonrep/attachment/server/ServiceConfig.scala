package uk.gov.hmrc.nonrep.attachment
package server

import java.net.URI
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.stream.RestartSettings
import org.apache.pekko.stream.connectors.s3.S3Settings
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

object ServiceConfig {
  val appName     = "attachment-processor"
}

class ServiceConfig(val servicePort: Int = 8000) {

  val appName = ServiceConfig.appName
  val port: Int   = sys.env.get("REST_PORT").fold(servicePort)(_.toInt)
  val env: String = sys.env.getOrElse("ENV", "local")

  def isSandbox: Boolean = !Set("dev", "qa", "staging", "production").contains(env)

  val queueUrl: String = sys.env.getOrElse("ATTACHMENT_SQS", "http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/local-nonrep-attachment-queue")

//  val queueUrl: String = sqsSystemProperty

  private[server] def sqsSystemProperty: String =
    sys.env.getOrElse(
      "ATTACHMENT_SQS",
      throw new IllegalStateException(
        "System property SQS queue url connection not set. This is required by the service to create a SQS queue message when necessary."
      )
    )

  private def findPort(port: Int, securePort: Boolean): Int = (port, securePort) match {
    case (-1, true) => 443
    case (-1, false) => 80
    case (port, _) => port
  }

  val attachmentsBucket: String = s"$env-nonrep-attachment-data"

  val elasticSearchUri: URI                  = URI.create(sys.env.getOrElse("ELASTICSEARCH", "http://elasticsearch.nrs"))
  val isElasticSearchProtocolSecure: Boolean = elasticSearchUri.toURL.getProtocol == "https"
  val elasticSearchHost: String              = elasticSearchUri.getHost
  val elasticSearchPort: Int              = findPort(elasticSearchUri.getPort, isElasticSearchProtocolSecure)

  private val configFile = new java.io.File(s"/etc/config/CONFIG_FILE")

  val config: Config =
    if configFile.exists() then ConfigFactory.parseFile(configFile).resolve()
    else ConfigFactory.load("application.conf")

  val refreshPolicy: String = config.getConfig("metastore").getString("refresh_policy")

  private val signaturesParams           = config.getObject(s"$appName.signatures").toConfig
  private val signaturesServiceUri       = URI.create(signaturesParams.getString("service-url"))
  val isSignaturesServiceSecure: Boolean = signaturesServiceUri.toURL.getProtocol == "https"
  val signaturesServiceHost: String      = signaturesServiceUri.getHost
  val signaturesServicePort: Int         = findPort(signaturesServiceUri.getPort, isSignaturesServiceSecure)
  val signingProfile: String             = signaturesParams.getString("signing-profile")

  private val systemParams         = config.getObject(s"$appName.system-params").toConfig
  val maxBufferSize: Int           = systemParams.getInt("maxBufferSize")
  val maxBatchSize: Int            = systemParams.getInt("maxBatchSize")
  val closeOnEmptyReceive: Boolean = systemParams.getBoolean("closeOnEmptyReceive")
  val waitTimeSeconds: Int         = systemParams.getInt("waitTimeSeconds")
  val messagesPerSecond: Int       = systemParams.getInt("messagesPerSecond")

  val awsSettings: S3Settings = S3Settings(config.getConfig(S3Settings.ConfigPath))

  private val glacierParams = config.getObject(s"$appName.glacier").toConfig
  val awsGlacierSettings = awsSettings.withEndpointUrl(glacierParams.getString("glacier-url"))
  val glacierUrl:String = awsGlacierSettings.endpointUrl.getOrElse(
    throw new IllegalStateException(
      "System property glacier-url is not set."))

  // see https://doc.akka.io/libraries/akka-core/current/stream/stream-error.html#delayed-restarts-with-a-backoff-operator
  private val sqsRestartConfig = config.getObject(s"$appName.sqs.restart_policy").toConfig

  val sqsRestartSettings = RestartSettings(
    minBackoff = Duration.fromNanos(sqsRestartConfig.getDuration("minBackoff", TimeUnit.NANOSECONDS)), // 1.seconds,
    maxBackoff = Duration.fromNanos(sqsRestartConfig.getDuration("maxBackoff", TimeUnit.NANOSECONDS)), //3.seconds,
    randomFactor = sqsRestartConfig.getDouble("randomFactor") //0.2 // adds 20% "noise" to vary the intervals slightly
  ).withMaxRestarts(sqsRestartConfig.getInt("maxRestarts"), Duration.fromNanos(sqsRestartConfig.getDuration("maxRestartsDuration", TimeUnit.NANOSECONDS))) // limits the amount of restarts to 20 within 5 minutes

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
