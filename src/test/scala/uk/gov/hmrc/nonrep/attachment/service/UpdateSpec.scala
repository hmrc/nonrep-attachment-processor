package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}

import java.util.UUID
import scala.concurrent.duration.DurationInt

class UpdateSpec extends BaseSpec {

  import TestServices._

  "Update metastore service" should {

    "Update metastore document with attachment info" in {
      import TestServices.success._
      val messageId = testSQSMessageIds.head
      val attachmentInfo = AttachmentInfo(testAttachmentId, messageId, s"$testAttachmentId.zip", submissionId = Some(UUID.randomUUID().toString))
      val vaultName = UUID.randomUUID().toString
      val archiveId = UUID.randomUUID().toString
      val archived = Right(ArchivedAttachment(attachmentInfo, vaultName, archiveId))

      val source = TestSource.probe[EitherErr[ArchivedAttachment]]
      val sink = TestSink.probe[EitherErr[AttachmentInfo]]

      val (pub, sub) = source.via(updateService.updateMetastore).toMat(sink)(Keep.both).run()
      pub.sendNext(archived).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.attachmentId shouldBe attachmentInfo.attachmentId
      result.toOption.get.s3ObjectKey shouldBe attachmentInfo.s3ObjectKey
      result.toOption.get.message shouldBe attachmentInfo.message
    }

    "Report update metastore failure" in {
      import TestServices.failure._
      val messageId = testSQSMessageIds.head
      val attachmentInfo = AttachmentInfo(testAttachmentId, messageId, s"$testAttachmentId.zip")
      val vaultName = UUID.randomUUID().toString
      val archiveId = UUID.randomUUID().toString
      val archived = Right(ArchivedAttachment(attachmentInfo, vaultName, archiveId))

      val source = TestSource.probe[EitherErr[ArchivedAttachment]]
      val sink = TestSink.probe[EitherErr[AttachmentInfo]]

      val (pub, sub) = source.via(updateService.updateMetastore).toMat(sink)(Keep.both).run()
      pub.sendNext(archived).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message shouldBe "failure"
    }

    "use new request signing parameters when needed" in {
      import TestServices.success._

      val sink = TestSink.probe[RequestsSignerParams]
      val sub = updateService.signerParams.throttle(1, 1.second).runWith(sink)

      val key1 = sub.request(1).expectNext().credentials.accessKeyId()
      val key2 = sub.request(1).expectNext().credentials.accessKeyId()

      key1 != key2 shouldBe true
    }

  }

}
