package uk.gov.hmrc.nonrep.attachment
package service

import akka.util.ByteString

class ProcessorSpec extends BaseSpec {

  import TestServices._

  "attachments processor for happy path" should {
    import TestServices.success._

    val processor = new ProcessorService(testApplicationSink) {
      override def storage: Storage = storageService
      override def queue: Queue = queueService
    }

    "process attachments" in {
      val attachmentId = "738bcba6-7f9e-11ec-8768-3f8498104f38"
      val file = ByteString(sampleAttachment)

      /*
      This test will change with every new processing step added
       */
      val result = processor.execute.run()
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.info.key shouldBe attachmentId
      result.toOption.get.content.toArray[Byte] shouldBe file
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
      result.left.toOption.get.message shouldBe s"Error getting attachment 738bcba6-7f9e-11ec-8768-3f8498104f38 from S3 ${config.attachmentsBucket}"
    }
  }
}
