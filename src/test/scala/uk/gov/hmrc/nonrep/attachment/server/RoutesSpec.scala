package uk.gov.hmrc.nonrep.attachment
package server

import fr.davit.pekko.http.metrics.core.scaladsl.server.HttpMetricsDirectives.pathLabeled
import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpRequest, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.RouteTestTimeout
import uk.gov.hmrc.nonrep.BuildInfo
import uk.gov.hmrc.nonrep.attachment.app.json.JsonFormats.buildVersionJsonFormat

import scala.concurrent.Future
import scala.concurrent.duration.*

class RoutesSpec extends BaseSpec {

  lazy val testKit: ActorTestKit                                       = ActorTestKit()
  implicit def typedSystem: ActorSystem[Nothing]                       = testKit.system
  override def createActorSystem(): org.apache.pekko.actor.ActorSystem = testKit.system.toClassic

  implicit val timeout: RouteTestTimeout = RouteTestTimeout(10 second span)

  implicit val config: ServiceConfig = new ServiceConfig()

  trait Setup {
    val defaultF: Future[Done] = Future {
      Thread.sleep(10000)
      Done
    }
    val routes: Routes         = new Routes(defaultF)
  }

  "Attachment Processor routes" should {
    "return version information" in new Setup {
      val request: HttpRequest = Get(s"/${config.appName}/version")

      request ~> routes.serviceRoutes ~> check {
        status                           shouldBe StatusCodes.OK
        contentType                      shouldBe ContentTypes.`application/json`
        responseAs[BuildVersion].version shouldBe BuildInfo.version
      }
    }

    "reply to ping request on service url" in new Setup {
      val request: HttpRequest = Get(s"/${config.appName}/ping")

      request ~> routes.serviceRoutes ~> check {
        status             shouldBe StatusCodes.OK
        contentType        shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "pong"
      }
    }

    "reply to ping request" in new Setup {
      val request: HttpRequest = Get(s"/ping")

      request ~> routes.serviceRoutes ~> check {
        status             shouldBe StatusCodes.OK
        contentType        shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "pong"
      }
    }

    // the below tests require overrides to avoid conflicting behaviour
    "catch exception" in new Setup {
      override val routes: Routes = new Routes(defaultF) {

        override val versionPath: Route = pathLabeled("version") {
          pathEndOrSingleSlash {
            get {
              _.complete(IllegalArgumentException("something bad happened")) // invalid code to force an exception
            }
          }
        }
      }

      val request: HttpRequest = Get(s"/${config.appName}/version")

      request ~> routes.serviceRoutes ~> check {
        status                shouldBe StatusCodes.InternalServerError
        contentType           shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldEqual "Internal NRS attachments processor error"
      }
    }

    "reply to ping when processor stopped working" in new Setup {
      override val routes: Routes = new Routes(Future.successful(Done))

      val request: HttpRequest = Get(s"/${config.appName}/ping")
      request ~> routes.serviceRoutes ~> check {
        status             shouldBe StatusCodes.InternalServerError
        contentType        shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "Processing of attachments is finished"
      }
    }
  }
}
