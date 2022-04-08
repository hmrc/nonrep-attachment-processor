package uk.gov.hmrc.nonrep.attachment
package server

import akka.Done
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.server.Directives.handleExceptions
import akka.http.scaladsl.testkit.RouteTestTimeout
import uk.gov.hmrc.nonrep.BuildInfo
import uk.gov.hmrc.nonrep.attachment.utils.JsonFormats._

import scala.concurrent.Future
import scala.concurrent.duration._

class RoutesSpec extends BaseSpec {

  lazy val testKit = ActorTestKit()
  implicit def typedSystem = testKit.system
  override def createActorSystem(): akka.actor.ActorSystem = testKit.system.toClassic
  implicit val timeout = RouteTestTimeout(10 second span)

  implicit val config: ServiceConfig = new ServiceConfig()

  val routes = new Routes(Future{
    Thread.sleep(1000)
    Done
  })

  "Attachment Processor routes" should {

    "catch exception" in {
      val request = Get(s"/${config.appName}/version") ~> handleExceptions(routes.exceptionHandler) {
        _.complete((1 / 0).toString)
      } ~> check {
        responseAs[String] shouldEqual "Internal NRS attachments processor error"
      }
    }

    "return version information" in {
      Get(s"/${config.appName}/version") ~> routes.serviceRoutes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`application/json`
        responseAs[BuildVersion].version shouldBe BuildInfo.version
      }
    }

    "reply to ping request on service url" in {
      Get(s"/${config.appName}/ping") ~> routes.serviceRoutes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "pong"
      }
    }

    "reply to ping when processor stopped working" in {
      Get(s"/${config.appName}/ping") ~> new Routes(Future(Done)).serviceRoutes ~> check {
        status shouldBe StatusCodes.InternalServerError
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "Processing of attachments is finished"
      }
    }

    "reply to ping request" in {
      Get(s"/ping") ~> routes.serviceRoutes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/plain(UTF-8)`
        responseAs[String] shouldBe "pong"
      }
    }
  }

}
