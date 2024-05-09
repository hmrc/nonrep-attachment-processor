package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.testkit.TestSubscriber
import software.amazon.awssdk.services.sqs.model.Message

class ProcessorSpec extends BaseSpec {

  import TestServices._
  import TestServices.success._

  "attachments processor for happy path" should {

    val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
      new ProcessorService(testApplicationSink) {
        override def getMessages: Source[Message, NotUsed] = queueService.getMessages
        override def parseMessage: Flow[Message, EitherErr[AttachmentInfo], NotUsed] = queueService.parseMessages
        override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = queueService.deleteMessage
        override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storageService.downloadAttachment
        override def unpackBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed] = zipperService.extractBundle
        override def repackBundle: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed] = zipperService.createBundle
        override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = signService.signing
        override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacierService.archive
        override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] = updateService.updateMetastore
        override def deleteBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = storageService.deleteAttachment
    }

    "process attachments" in {
      val result = processor.execute.run().request(1).expectNext().toOption.get

      result.key shouldBe testAttachmentId
      result.message shouldBe testSQSMessageIds.head
    }
  }

  "for failure scenarios attachments processor" should {

    "report a warning when an attachment cannot be downloaded from s3" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink) {
          override def getMessages: Source[Message, NotUsed] = queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = failure.storageService.downloadAttachment
        }

      val result = processor.execute.run()
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe WARN
      result.left.toOption.get.message shouldBe s"Error getting attachment $testAttachmentId from S3 ${config.attachmentsBucket}"
    }

    "report a warning for random failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink) {
          override def getMessages: Source[Message, NotUsed] = queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = signService.signing
        }

      val result = processor.execute.run()
        .request(1)
        .expectNext()

      println("------------------ RESULT -----------------------")
      println(result)
      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe WARN
      result.left.toOption.get.message shouldBe s"Response status 500 Internal Server Error from signatures service ${config.signaturesServiceHost}"
    }

    "report a warning for signing failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink) {
          override def getMessages: Source[Message, NotUsed] = queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = failure.signService.signing
        }

      val result = processor.execute.run()
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe WARN
      //      result.left.toOption.get.message shouldBe s"Response status 500 Internal Server Error from signatures service ${config.signaturesServiceHost}"
    }

    "report an error for a glacier failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink) {
          override def getMessages: Source[Message, NotUsed] = queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = signService.signing
          override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = failure.glacierService.archive
        }


      val result = processor.execute.run().request(1).expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message.startsWith("Error uploading attachment") shouldBe true
    }

    "report an error for update metastore failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink) {
          override def getMessages: Source[Message, NotUsed] = queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = signService.signing
          override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacierService.archive
          override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] = failure.updateService.updateMetastore
        }

      val result = processor.execute.run().request(1).expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message shouldBe "failure"
    }

    "report an error for parsing SQS message failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink) {
          override def getMessages: Source[Message, NotUsed] = failure.queueService.getMessages
        }

      processor.execute.run().request(1).expectComplete()
    }

    "report an error for deleting SQS message failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink) {
          override def getMessages: Source[Message, NotUsed] = queueService.getMessages
          override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = failure.queueService.deleteMessage
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = signService.signing
          override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacierService.archive
          override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] = updateService.updateMetastore
        }

      val result = processor.execute.run().request(1).expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message shouldBe "Delete SQS message failure"
    }

    "report error for deleting s3 bundle failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink) {
          override def getMessages: Source[Message, NotUsed] = queueService.getMessages
          override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = queueService.deleteMessage
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = signService.signing
          override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacierService.archive
          override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] = updateService.updateMetastore

          override def deleteBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = failure.storageService.deleteAttachment

        }

      val result = processor.execute.run().request(1).expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message shouldBe "failure"
    }
  }
}
