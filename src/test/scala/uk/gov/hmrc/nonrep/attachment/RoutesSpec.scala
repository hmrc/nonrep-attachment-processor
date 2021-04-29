package uk.gov.hmrc.nonrep.attachment

import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.nonrep.attachment.model.BuildVersion


class RoutesSpec extends BaseSpec with ScalaFutures with ScalatestRouteTest {

  val prefix = "attachment-processor"

  "Attachment Processor routes" should {
    "return pong for a ping" in {
      val routesService = new Routes()

      val request = Get(s"/$prefix/ping")

      request ~> routesService.routes ~> check {
        status should ===(StatusCodes.OK)
      }
    }
    "return version information" in {
      val routesService = new Routes()

      val request = Get(s"/$prefix/version")

      request ~> routesService.routes ~> check {
        status should ===(StatusCodes.OK)
        contentType should ===(ContentTypes.`application/json`)
        responseAs[BuildVersion].version should ===(BuildInfo.version)
      }
    }
  }

}
