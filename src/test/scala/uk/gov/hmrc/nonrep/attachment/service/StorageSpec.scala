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
      val messageId = testSQSMessageIds.head
      val attachmentContent = ByteString(sampleAttachment)
      val attachment = Right(AttachmentInfo(messageId, testAttachmentId))

      val source = TestSource.probe[EitherErr[AttachmentInfo]]
      val sink = TestSink.probe[EitherErr[AttachmentContent]]

      val (pub, sub) = source.via(storageService.downloadAttachment).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.info.key shouldBe attachment.toOption.get.key
      result.toOption.get.info.message shouldBe messageId
      result.toOption.get.content shouldBe attachmentContent
    }

    "delete file from S3" in {
      val messageId = testSQSMessageIds.head
      val attachment = Right(AttachmentInfo(messageId, testAttachmentId))

      val source = TestSource.probe[EitherErr[AttachmentInfo]]
      val sink = TestSink.probe[EitherErr[AttachmentInfo]]

      val (pub, sub) = source.via(storageService.deleteAttachment).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.key shouldBe attachment.toOption.get.key
      result.toOption.get.message shouldBe messageId
    }
  }

  "Storage service for failure scenarios" should {
    import TestServices.failure._

    "report when downloading file from S3 fails" in {
      val messageId = testSQSMessageIds.head
      val attachment = Right(AttachmentInfo(messageId, testAttachmentId))

      val source = TestSource.probe[EitherErr[AttachmentInfo]]
      val sink = TestSink.probe[EitherErr[AttachmentContent]]

      val (pub, sub) = source.via(storageService.downloadAttachment).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result = sub
        .request(1)
        .expectComplete()
    }

    "report when deleting file from S3 fails" in {
      val messageId = testSQSMessageIds.head
      val attachment = Right(AttachmentInfo(messageId, testAttachmentId))

      val source = TestSource.probe[EitherErr[AttachmentInfo]]
      val sink = TestSink.probe[EitherErr[AttachmentInfo]]

      val (pub, sub) = source.via(storageService.deleteAttachment).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.message shouldBe "failure"
    }

  }
}
