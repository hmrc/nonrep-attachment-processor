package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import software.amazon.awssdk.services.sqs.model.Message

class QueueSpec extends BaseSpec {

  import TestServices._

  "Queue service" should {
    import TestServices.success._
    "SqsSourceSettings should have" in {
      queueService.settings.maxBatchSize shouldBe config.maxBatchSize
      queueService.settings.maxBufferSize shouldBe config.maxBufferSize
      queueService.settings.waitTimeSeconds shouldBe config.waitTimeSeconds
      queueService.settings.closeOnEmptyReceive shouldBe config.closeOnEmptyReceive
    }

    "Message from source should have" in {
      val sink = TestSink.probe[Message]

      val sub = queueService.getMessages.runWith(sink)
      val result = sub
        .request(1)
        .expectNext()

      testSQSMessageIds should contain(result.receiptHandle())
    }

    "Delete messages" when {
      "the s3 object can not be downloaded" in {
        val sink = TestSink.probe[EitherErr[AttachmentInfo]]
        val (_, sub) = queueService.getMessages
          .via(queueService.parseMessages)
          .via(TestServices.failure.storageService.downloadAttachment)
          .map(_.map(_.info))
          .via(queueService.deleteMessage)
          .toMat(sink)(Keep.both)
          .run()

        val result = sub
          .request(1)
          .expectNext()

        result.isLeft shouldBe true
        result.left.toOption.get shouldBe a [ErrorMessageWithDeleteSQSMessage]
        result.left.toOption.get.message shouldBe "failed to download 738bcba6-7f9e-11ec-8768-3f8498104f38.zip attachment bundle from s3 local-nonrep-attachment-data"
      }

      "completed processing" in {
        val sink = TestSink.probe[EitherErr[AttachmentInfo]]
        val (_, sub) = queueService.getMessages
          .via(queueService.parseMessages)
          .via(queueService.deleteMessage)
          .toMat(sink)(Keep.both)
          .run()

        val result = sub
          .request(1)
          .expectNext()

        result.isRight shouldBe true
        result.toOption.get.attachmentId shouldBe testAttachmentId
      }
    }

    "Parse messages properly" in {
      val sink = TestSink.probe[EitherErr[AttachmentInfo]]

      val (_, sub) = queueService
        .getMessages
        .via(queueService.parseMessages)
        .toMat(sink)(Keep.both)
        .run()

      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.attachmentId shouldBe testAttachmentId
    }

    "For failure scenarios Queue service" should {
      import TestServices.failure._

      "Report parsing message failure" in {
        val sink = TestSink.probe[EitherErr[AttachmentInfo]]

        val (_, sub) = queueService
          .getMessages
          .via(queueService.parseMessages)
          .toMat(sink)(Keep.both)
          .run()

        val result = sub.request(1).expectNext()

        result.isLeft shouldBe true
        result.left.toOption.get.severity shouldBe ERROR
        result.left.toOption.get.message should startWith regex "Parsing SQS message failure"
      }

      "Report delete message failure" in {
        val source = TestSource.probe[EitherErr[AttachmentInfo]]
        val sink = TestSink.probe[EitherErr[AttachmentInfo]]
        val messageId = testSQSMessageIds.head
        val attachment = Right(AttachmentInfo(testAttachmentId, messageId, s"$testAttachmentId.zip"))

        val (pub, sub) = source.via(queueService.deleteMessage).toMat(sink)(Keep.both).run()
        pub.sendNext(attachment).sendComplete()

        val result = sub
          .request(1)
          .expectNext()

        result.isLeft shouldBe true
        result.left.toOption.get.severity shouldBe ERROR
        result.left.toOption.get.message shouldBe "Delete SQS message failure"
      }
    }
  }
}