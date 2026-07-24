package uk.gov.hmrc.nonrep.attachment
package server

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.directives.MethodDirectives.get
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import fr.davit.pekko.http.metrics.core.scaladsl.server.HttpMetricsDirectives.{metrics, pathLabeled}
import fr.davit.pekko.http.metrics.prometheus.marshalling.PrometheusMarshallers.*
import uk.gov.hmrc.nonrep.attachment.app.metrics.Prometheus
import io.prometheus.client.exporter.common.TextFormat
import org.slf4j.Logger
import uk.gov.hmrc.nonrep.BuildInfo
import uk.gov.hmrc.nonrep.attachment.app.metrics.Prometheus.*
import uk.gov.hmrc.nonrep.attachment.app.json.JsonFormats.buildVersionJsonFormat
import uk.gov.hmrc.nonrep.attachment.utils.MessageCount

import java.io.StringWriter
import scala.concurrent.Future

object Routes {
  def apply(processor: Future[Done])(using ActorSystem[?], ServiceConfig) = new Routes(processor)
}

class Routes(processor: Future[Done])(using system: ActorSystem[?], config: ServiceConfig) {

  val log: Logger = system.log

  val exceptionHandler: ExceptionHandler = ExceptionHandler { case error =>
    log.error("Internal server error", error)
    complete(HttpResponse(InternalServerError, entity = "Internal NRS attachments processor error"))
  }

  def dumpMetrics: String =
    val writer = new StringWriter()
    TextFormat.write004(writer, Prometheus.prometheus.metricFamilySamples())
    writer.toString.split("\n").filterNot( _.startsWith("#")).mkString("\n")

  def logStatus(): Unit = {
    system.log.info(
      s"""Ping Status
         |Processor.isCompleted:  ${processor.isCompleted}
         |Messages Processed: ${MessageCount.msgCount}
         |Memory Total: ${Runtime.getRuntime.totalMemory()}
         |Memory Free: ${Runtime.getRuntime.freeMemory()}
         |Thread Count: ${Thread.activeCount()}
         |
         |""".stripMargin
      + dumpMetrics
    )

  }

  val pingPath: Route = pathLabeled("ping") {
    get {
      complete {
        if processor.isCompleted then
          logStatus()
          HttpResponse(StatusCodes.InternalServerError, entity = "Processing of attachments is finished")
        else
          logStatus()
          HttpResponse(StatusCodes.OK, entity = "pong")
      }
    }
  }

  val versionPath: Route = pathLabeled("version") {
    pathEndOrSingleSlash {
      get {
        complete(StatusCodes.OK, BuildVersion(version = BuildInfo.version))
      }
    }
  }

  val metricsPath: Route = pathLabeled("metrics") {
    get {
      metrics(registry)
    }
  }

  lazy val serviceRoutes: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("attachment-processor") {
        pingPath
          ~ versionPath
      }
        ~ pingPath
        ~ metricsPath
    }
}
