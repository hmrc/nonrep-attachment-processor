package uk.gov.hmrc.nonrep.attachment
package server

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.pathLabeled
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.pathLabeled
import uk.gov.hmrc.nonrep.BuildInfo
import uk.gov.hmrc.nonrep.attachment.utils.JsonFormats._

object Routes {
  def apply()(implicit system: ActorSystem[_], config: ServiceConfig) = new Routes()
}

class Routes()(implicit val system: ActorSystem[_], config: ServiceConfig) {

  val log = system.log
  val exceptionHandler = ExceptionHandler {
    case x => {
      log.error("Internal server error", x)
      complete(HttpResponse(InternalServerError, entity = "Internal NRS attachments processor error"))
    }
  }

  lazy val serviceRoutes: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("attachment-processor") {
        pathLabeled("ping") {
          get {
            complete(HttpResponse(StatusCodes.OK, entity = "pong"))
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
      }
    }
}