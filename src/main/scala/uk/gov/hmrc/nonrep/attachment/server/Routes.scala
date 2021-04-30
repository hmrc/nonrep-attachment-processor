package uk.gov.hmrc.nonrep.attachment
package server

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes.InternalServerError
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import fr.davit.akka.http.metrics.core.scaladsl.server.HttpMetricsDirectives.pathLabeled

object Routes {
  def apply()(implicit system: ActorSystem[_], config: ServiceConfig) = new Routes()
}

class Routes()(implicit val system: ActorSystem[_], config: ServiceConfig) {

  import io.circe.generic.auto._
  import io.circe.syntax._

  val log = system.log
  val exceptionHandler = ExceptionHandler {
    case x => {
      log.error("Internal server error", x)
      complete(HttpResponse(InternalServerError, entity = "Internal NRS attachment-processor API error"))
    }
  }

  def completeAsJson(httpCode: StatusCode, json: String) = complete(
    HttpResponse(
      httpCode,
      entity = HttpEntity(ContentTypes.`application/json`, json)))

  lazy val serviceRoutes: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("attachment-processor") {
        pathLabeled("ping") {
          get {
            complete(HttpResponse(StatusCodes.OK, entity = "PONG!"))
          }
        } ~ pathLabeled("version") {
          pathEndOrSingleSlash {
            get {
              completeAsJson(StatusCodes.OK, BuildVersion(version = BuildInfo.version).asJson.noSpaces)            }
          }
        }
      } ~ pathLabeled("ping") {
        get {
          complete(HttpResponse(StatusCodes.OK, entity = "PONG!"))
        }
      }
    }
}