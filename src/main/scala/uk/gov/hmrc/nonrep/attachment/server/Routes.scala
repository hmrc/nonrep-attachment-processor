package uk.gov.hmrc.nonrep.attachment
package server

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes.InternalServerError
import org.apache.pekko.http.scaladsl.model.{HttpResponse, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.directives.MethodDirectives.get
import org.apache.pekko.http.scaladsl.server.{ExceptionHandler, Route}
import fr.davit.pekko.http.metrics.core.scaladsl.server.HttpMetricsDirectives.{metrics, pathLabeled}
import fr.davit.pekko.http.metrics.prometheus.marshalling.PrometheusMarshallers._
import org.slf4j.Logger
import uk.gov.hmrc.nonrep.BuildInfo
import uk.gov.hmrc.nonrep.attachment.metrics.Prometheus._
import uk.gov.hmrc.nonrep.attachment.utils.JsonFormats._

import scala.concurrent.Future

object Routes {
  def apply(processor: Future[Done])(implicit system: ActorSystem[_], config: ServiceConfig) = new Routes(processor)
}

class Routes(processor: Future[Done])(implicit val system: ActorSystem[_], config: ServiceConfig) {

  val log: Logger = system.log
  val exceptionHandler: ExceptionHandler = ExceptionHandler {
    case error =>
      log.error("Internal server error", error)
      complete(HttpResponse(InternalServerError, entity = "Internal NRS attachments processor error"))
  }

  lazy val serviceRoutes: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("attachment-processor") {
        pathLabeled("ping") {
          get {
            complete {
              if (processor.isCompleted)
                HttpResponse(StatusCodes.InternalServerError, entity = "Processing of attachments is finished")
              else
                HttpResponse(StatusCodes.OK, entity = "pong")
            }
          }
        } ~ pathLabeled("version") {
          pathEndOrSingleSlash {
            get {
              complete(StatusCodes.OK, BuildVersion(version = BuildInfo.version))
            }
          }
        }
      } ~ pathLabeled("ping") {
        get {
          complete(HttpResponse(StatusCodes.OK, entity = "pong"))
        }
      } ~ pathLabeled("metrics") {
        get {
          metrics(registry)
        }
      }
    }
}