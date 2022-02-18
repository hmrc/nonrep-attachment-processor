package uk.gov.hmrc.nonrep.attachment
package service

import java.util.UUID

import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.util.ByteString

class StorageSpec extends BaseSpec {
  import TestServices._

  "Storage service on happy path" should {
    import TestServices.success._

    "download file from S3" in {
      val messageId = UUID.randomUUID().toString
      val attachmentId = "738bcba6-7f9e-11ec-8768-3f8498104f38"
      val attachmentContent = ByteString(sampleAttachment)
      val attachment = AttachmentInfo(messageId, attachmentId)

      val source = TestSource.probe[AttachmentInfo]
      val sink = TestSink.probe[EitherErr[AttachmentContent]]

      val (pub, sub) = source.via(storageService.downloadAttachment()).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.info.key shouldBe attachmentId
      result.toOption.get.info.message shouldBe messageId
      result.toOption.get.content shouldBe attachmentContent
    }
  }

  "Storage service for failure scenarios" should {
    import TestServices.failure._

    "report when downloading file from S3 fails" in {
      val messageId = UUID.randomUUID().toString
      val attachment = AttachmentInfo(messageId, "738bcba6-7f9e-11ec-8768-3f8498104f38")

      val source = TestSource.probe[AttachmentInfo]
      val sink = TestSink.probe[EitherErr[AttachmentContent]]

      val (pub, sub) = source.via(storageService.downloadAttachment()).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe false
      result.left.toOption.get.message shouldBe s"Error getting attachment ${attachment.key} from S3 ${config.attachmentsBucket}"
    }

  }
}
