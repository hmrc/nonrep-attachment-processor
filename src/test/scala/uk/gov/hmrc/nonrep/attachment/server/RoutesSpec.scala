package uk.gov.hmrc.nonrep.attachment
package server

import fr.davit.pekko.http.metrics.core.scaladsl.server.HttpMetricsDirectives.pathLabeled
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.{complete, get, handleExceptions, pathEndOrSingleSlash}
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import uk.gov.hmrc.nonrep.BuildInfo
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.server.Route
import uk.gov.hmrc.nonrep.attachment.app.json.JsonFormats.*
import uk.gov.hmrc.nonrep.attachment.app.json.JsonFormats.buildVersionJsonFormat

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.concurrent.ExecutionContext.Implicits.global

class RoutesSpec extends BaseSpec {

  lazy val testKit: ActorTestKit                                       = ActorTestKit()
  implicit def typedSystem: ActorSystem[Nothing]                       = testKit.system
  override def createActorSystem(): org.apache.pekko.actor.ActorSystem = testKit.system.toClassic

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(10 second span)

  implicit val config: ServiceConfig = new ServiceConfig()

  trait Setup {
    val routes = new Routes(Future {
      Thread.sleep(10000)
      Done
    })
  }

  "Attachment Processor routes" should {

    "catch exception" in new Setup {
      override val routes = new Routes(Future {
        Thread.sleep(10000)
        Done
      }) {

        override val versionPath: Route = pathLabeled("version") {
          pathEndOrSingleSlash {
            get {
              _.complete((1 / 0).toString) // invalid code to force an exception
            }
          }
        }
      }

      val request: HttpRequest = Get(s"/${config.appName}/version")

      request ~> routes.serviceRoutes ~> check {
        status shouldBe StatusCodes.InternalServerError
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldEqual "Internal NRS attachments processor error"
      }
    }

    "return version information" in new Setup {
      val request: HttpRequest = Get(s"/${config.appName}/version")

      request ~> routes.serviceRoutes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`application/json`
        responseAs[BuildVersion].version shouldBe BuildInfo.version
      }
    }

    "reply to ping request on service url" in new Setup {
      val request: HttpRequest = Get(s"/${config.appName}/ping")

      request ~> routes.serviceRoutes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "pong"
      }
    }

    "reply to ping when processor stopped working" in new Setup {
      override val routes = new Routes(Future(Done))

      val request: HttpRequest = Get(s"/${config.appName}/ping")
      request ~> routes.serviceRoutes ~> check {
        status shouldBe StatusCodes.InternalServerError
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "Processing of attachments is finished"
      }
    }

    "reply to ping request" in new Setup {
      val request: HttpRequest = Get(s"/ping")

      request ~> routes.serviceRoutes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "pong"
      }
    }
  }
}
