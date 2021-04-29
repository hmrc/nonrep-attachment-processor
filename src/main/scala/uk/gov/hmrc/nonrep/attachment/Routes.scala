package uk.gov.hmrc.nonrep.attachment

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{handleExceptions, pathEndOrSingleSlash, pathPrefix, _}
import akka.http.scaladsl.server.directives.MethodDirectives.get
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import uk.gov.hmrc.nonrep.attachment.model.BuildVersion

import scala.concurrent.ExecutionContext

class Routes()(implicit val system: ActorSystem, ec: ExecutionContext) {


  lazy val log = Logging.withMarker(system, classOf[Routes])

  val exceptionHandler = ExceptionHandler {
    case x => {
      log.error(x, s"Internal server error, caused by ${x.getCause}")
      complete(HttpResponse(500, entity = "Internal NRS Signing API error"))
    }
  }

  def completeAsJson(httpCode: StatusCode, json: String) = complete(
    HttpResponse(
      httpCode,
      entity = HttpEntity(ContentTypes.`application/json`, json)))

  lazy val routes: Route =
    handleExceptions(exceptionHandler) {
      pathPrefix("attachment-processor") {
        pathPrefix("ping") {
          get {
            complete(HttpResponse(200, entity = "pong"))
          }
        }
      }
      ~ pathPrefix("version") {
        pathEndOrSingleSlash {
          get {
            completeAsJson(StatusCodes.OK, BuildVersion(version = BuildInfo.version).asJson.noSpaces)
          }
        }
      }
    }
}
