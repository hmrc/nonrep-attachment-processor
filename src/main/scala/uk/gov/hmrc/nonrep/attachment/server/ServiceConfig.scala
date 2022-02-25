package uk.gov.hmrc.nonrep.attachment
package server

import java.net.URI

import com.typesafe.config.{Config, ConfigFactory}

import scala.jdk.CollectionConverters._

class ServiceConfig(val servicePort: Int = 8000) {

  implicit class OptionalConfig(val config: Config) {
    def getIntOption(key: String): Option[Int] = if (config.hasPath(key)) Some(config.getInt(key)) else None
  }

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


  private val systemParams = config.getObject(s"$appName.system-params").toConfig
  val sqsReadingRate: Int = systemParams.getInt("readingRate")
  val processingLimit: Int = systemParams.getInt("processingLimit")
  val resetLimitCounter: Int = systemParams.getInt("resetLimitCounter")
  val sqs: Option[String] = sys.env.get("ATTACHMENT_PROCESSOR_SQS")

  def repeatCount(prefix: String): Int = systemParams.getIntOption(s"${prefix}RepeatCount").getOrElse(3)

  def repeatDelay(prefix: String): Int = systemParams.getIntOption(s"${prefix}RepeatDelay").getOrElse(1000)
  override def toString =
    s"""
    appName: $appName
    port: $servicePort
    sqs: $sqs
    sqsReadingRate: $sqsReadingRate
    processingLimit: $processingLimit
    env: $env
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""

}