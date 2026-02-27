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
import org.slf4j.Logger
import uk.gov.hmrc.nonrep.BuildInfo
import uk.gov.hmrc.nonrep.attachment.app.metrics.Prometheus.*
import uk.gov.hmrc.nonrep.attachment.app.json.JsonFormats.buildVersionJsonFormat

import scala.concurrent.Future

object Routes {
  def apply(processor: Future[Done])(using ActorSystem[?], ServiceConfig) = new Routes(processor)
}

class Routes(processor: Future[Done])(using system: ActorSystem[?], config: ServiceConfig) {

  val log: Logger                        = system.log
  
  val exceptionHandler: ExceptionHandler = ExceptionHandler { case error =>
    log.error("Internal server error", error)
    complete(HttpResponse(InternalServerError, entity = "Internal NRS attachments processor error"))
  }

  val pingPath: Route = pathLabeled("ping") {
    get {
      complete {
        if processor.isCompleted then HttpResponse(StatusCodes.InternalServerError, entity = "Processing of attachments is finished")
        else HttpResponse(StatusCodes.OK, entity = "pong")
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
