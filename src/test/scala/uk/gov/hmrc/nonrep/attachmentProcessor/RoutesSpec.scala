package uk.gov.hmrc.nonrep.attachmentProcessor

import akka.http.scaladsl.client.RequestBuilding.Get


class RoutesSpec extends BaseSpec {

  "Attachment Processor routes" should {
    "return version information" in {
      val request = Get("/attachment-processor/version")

    }
  }

}
