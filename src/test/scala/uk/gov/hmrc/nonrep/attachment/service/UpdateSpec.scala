package uk.gov.hmrc.nonrep.attachment
package service

import java.util.UUID

import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSink, TestSource}

class UpdateSpec extends BaseSpec {

  import TestServices._

  "Update metastore service" should {

    "Update metastore document with attachment info" in {
      import TestServices.success._
      val messageId = testSQSMessageIds.head
      val attachmentInfo = AttachmentInfo(messageId, testAttachmentId, submissionId = Some(UUID.randomUUID().toString))
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
      result.toOption.get.key shouldBe attachmentInfo.key
      result.toOption.get.message shouldBe attachmentInfo.message
    }

    "Report update metastore failure" in {
      import TestServices.failure._
      val messageId = testSQSMessageIds.head
      val attachmentInfo = AttachmentInfo(messageId, testAttachmentId)
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

  }

}
