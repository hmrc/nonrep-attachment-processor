package uk.gov.hmrc.nonrep.attachment
package server

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ServiceConfigSpec extends AnyWordSpec with Matchers {

  implicit val config: ServiceConfig = new ServiceConfig()

  "ServiceConfig" should {
    "specify app name" in {
      config.appName shouldBe "attachment-processor"
    }
    "specify environment" in {
      config.env should not be empty
    }
    "be able to use default service port" in {
      config.port shouldBe config.servicePort
    }
  }
}