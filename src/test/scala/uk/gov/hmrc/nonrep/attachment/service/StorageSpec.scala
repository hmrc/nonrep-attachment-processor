package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.apache.pekko.util.ByteString

class StorageSpec extends BaseSpec {

  import TestServices.*

  "Storage service on happy path" should {
    import TestServices.success.*

    "download file from S3" in {
      val messageId         = testSQSMessageIds.head
      val attachmentContent = ByteString(sampleAttachment)
      val attachment        = Right(AttachmentInfo(testAttachmentId, messageId, s"$testAttachmentId.zip"))

      val source = TestSource.probe[EitherErr[AttachmentInfo]]
      val sink   = TestSink.probe[EitherErr[AttachmentContent]]

      val (pub, sub) = source.via(storageService.downloadAttachment).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      result.isRight                        shouldBe true
      result.toOption.get.info.attachmentId shouldBe attachment.toOption.get.attachmentId
      result.toOption.get.info.s3ObjectKey  shouldBe attachment.toOption.get.s3ObjectKey
      result.toOption.get.info.message      shouldBe messageId
      result.toOption.get.content           shouldBe attachmentContent
    }

    "delete file from S3" in {
      val messageId  = testSQSMessageIds.head
      val attachment = Right(AttachmentInfo(testAttachmentId, messageId, s"$testAttachmentId.zip"))

      val source = TestSource.probe[EitherErr[AttachmentInfo]]
      val sink   = TestSink.probe[EitherErr[AttachmentInfo]]

      val (pub, sub) = source.via(storageService.deleteAttachment).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      result.isRight                   shouldBe true
      result.toOption.get.attachmentId shouldBe attachment.toOption.get.attachmentId
      result.toOption.get.s3ObjectKey  shouldBe attachment.toOption.get.s3ObjectKey
      result.toOption.get.message      shouldBe messageId
    }
  }

  "Storage service for failure scenarios" should {
    import TestServices.failure.*

    "report when downloading file from S3 fails" in {
      val messageId  = testSQSMessageIds.head
      val attachment = Right(AttachmentInfo(testAttachmentId, messageId, s"$testAttachmentId.zip"))

      val source = TestSource.probe[EitherErr[AttachmentInfo]]
      val sink   = TestSink.probe[EitherErr[AttachmentContent]]

      val (pub, sub) = source.via(storageService.downloadAttachment).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      result.isRight                   shouldBe false
      result.left.toOption.get         shouldBe a[ErrorMessageWithDeleteSQSMessage]
      result.left.toOption.get.message shouldBe s"failed to download 738bcba6-7f9e-11ec-8768-3f8498104f38.zip attachment bundle from s3 ${config.attachmentsBucket}"
    }

    "report when deleting file from S3 fails" in {
      val messageId  = testSQSMessageIds.head
      val attachment = Right(AttachmentInfo(testAttachmentId, messageId, s"$testAttachmentId.zip"))

      val source = TestSource.probe[EitherErr[AttachmentInfo]]
      val sink   = TestSink.probe[EitherErr[AttachmentInfo]]

      val (pub, sub) = source.via(storageService.deleteAttachment).toMat(sink)(Keep.both).run()
      pub.sendNext(attachment).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      result.isLeft                    shouldBe true
      result.left.toOption.get.message shouldBe "failure"
    }

  }
}
