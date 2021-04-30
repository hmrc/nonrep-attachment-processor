package uk.gov.hmrc.nonrep.attachment

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, StatusCodes}
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.http.scaladsl.unmarshalling.Unmarshal
import org.scalatest.concurrent.ScalaFutures
import uk.gov.hmrc.nonrep.attachment.model.BuildVersion
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport
import io.circe.generic.auto._


class RoutesSpec extends BaseSpec with ScalaFutures with ScalatestRouteTest with FailFastCirceSupport{

  val prefix = "attachment-processor"

  "Attachment Processor routes" should {
    "return PONG! for a ping" in {
      val routesService = new Routes()

      val request = Get(s"/$prefix/ping")

      request ~> routesService.routes ~> check {
        status should === (StatusCodes.OK)
        // TODO: add an assertion for PONG!
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
