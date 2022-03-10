package uk.gov.hmrc.nonrep.attachment
package service

import akka.stream.testkit.TestSubscriber

class ProcessorSpec extends BaseSpec {

  import TestServices._

  "attachments processor for happy path" should {
    import TestServices.success._

    val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
      new ProcessorService(testApplicationSink) {
        override lazy val storage: Storage = storageService

        override lazy val queue: Queue = queueService

        override lazy val sign: Sign = signService

        override lazy val glacier: Glacier = glacierService
    }

    "process attachments" in {
      val result = processor.execute.run().request(1).expectNext().toOption.get

      result.key shouldBe testAttachmentId
    }
  }

  "for failure scenarios attachments processor" should {

    "report a warning when an attachment cannot be downloaded from s3" in {
      val processor = new ProcessorService(testApplicationSink) {
        override lazy val storage: Storage = failure.storageService

        override lazy val queue: Queue = success.queueService
      }

      val result = processor.execute.run()
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe WARN
      result.left.toOption.get.message shouldBe s"Error getting attachment $testAttachmentId from S3 ${config.attachmentsBucket}"
    }

    "report a warning for signing failure" in {
      val processor = new ProcessorService(testApplicationSink) {
        override lazy val storage: Storage = success.storageService

        override lazy val queue: Queue = success.queueService

        override lazy val sign: Sign = failure.signService
      }

      val result = processor.execute.run()
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe WARN
      result.left.toOption.get.message shouldBe s"Response status 500 Internal Server Error from signatures service ${config.signaturesServiceHost}"
    }

    "report an error for a glacier failure" in {
      val processor = new ProcessorService(testApplicationSink) {
        override lazy val storage: Storage = success.storageService
        override lazy val queue: Queue = success.queueService
        override lazy val sign: Sign = success.signService
        override lazy val glacier: Glacier = failure.glacierService
      }

      val result = processor.execute.run().request(1).expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message.startsWith("Error uploading attachment") shouldBe true
    }
  }
}
