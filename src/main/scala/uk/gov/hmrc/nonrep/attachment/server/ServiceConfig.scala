package uk.gov.hmrc.nonrep.attachment
package server

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

  override def toString =
    s"""
    appName: $appName
    port: $servicePort
    env: $env
    attachmentsBucket: $attachmentsBucket
    configFile: ${config.toString}"""

}