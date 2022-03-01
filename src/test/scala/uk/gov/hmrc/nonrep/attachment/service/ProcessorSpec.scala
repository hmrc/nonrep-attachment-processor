package uk.gov.hmrc.nonrep.attachment
package service

import akka.stream.testkit.TestSubscriber

class ProcessorSpec extends BaseSpec {

  import TestServices._

  "attachments processor for happy path" should {
    import TestServices.success._

    val processor: ProcessorService[TestSubscriber.Probe[EitherErr[ArchivedAttachmentContent]]] =
      new ProcessorService(testApplicationSink) {
        override def storage: Storage = storageService

        override def queue: Queue = queueService

        override def sign: Sign = signService

        override def glacier: Glacier = glacierService
    }

    "process attachments" in {
      val result = processor.execute.run().request(1).expectNext().toOption.get

      result.attachmentContent.info.key shouldBe testAttachmentId
      result.archiveId shouldBe archiveId
    }
  }

  "for failure scenarios attachments processor" should {

    "report a warning when an attachment cannot be downloaded from s3" in {
      val processor = new ProcessorService(testApplicationSink) {
        override def storage: Storage = failure.storageService

        override def queue: Queue = success.queueService
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
        override def storage: Storage = success.storageService

        override def queue: Queue = success.queueService

        override def sign: Sign = failure.signService
      }

      val result = processor.execute.run()
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe WARN
      result.left.toOption.get.message shouldBe s"Response status 500 Internal Server Error from signatures service ${config.signaturesServiceHost}"
    }
  }
}
