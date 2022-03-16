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
      config.env should not be null
    }

    "be able to use default service port" in {
      config.port shouldBe config.servicePort
    }

    "specify the glacier notifications SNS topic Arn" in {
      config.glacierNotificationsSnsTopicArn shouldBe "local"
    }

    "throw an error in non-local environments" when {
      "the mandatory GLACIER_SNS system property is not defined" in {
        intercept[Exception] {
          config.glacierSNSSystemProperty
        }.getMessage shouldBe "System property GLACIER_SNS is not set. This is required by the service to create a Glacier vault when necessary."
      }
    }
  }
}