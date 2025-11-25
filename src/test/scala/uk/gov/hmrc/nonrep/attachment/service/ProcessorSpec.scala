package uk.gov.hmrc.nonrep.attachment
package service

import io.prometheus.client.CollectorRegistry
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.testkit.TestSubscriber
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.TestServices.typedSystem
import uk.gov.hmrc.nonrep.attachment.app.metrics.Prometheus.attachmentSizeBucket

class ProcessorSpec extends BaseSpec {

  import TestServices.*
  import TestServices.success.*

  "attachments processor for happy path" should {

    val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
      new ProcessorService(testApplicationSink)(using typedSystem, config) {
        override def getMessages: Source[Message, NotUsed]                                                     = queueService.getMessages
        override def parseMessage: Flow[Message, EitherErr[AttachmentInfo], NotUsed]                           = queueService.parseMessages
        override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]        = queueService.deleteMessage
        override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed]    =
          storageService.downloadAttachment
        override def unpackBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed]          = zipperService.extractBundle
        override def repackBundle: Flow[EitherErr[SignedZipContent], EitherErr[AttachmentContent], NotUsed]    = zipperService.createBundle
        override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]         = signService.signing
        override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacierService.archive
        override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]  =
          updateService.updateMetastore
        override def deleteBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]         = storageService.deleteAttachment
      }

    "process attachments" in {
      val result = processor.execute.run().request(1).expectNext().toOption.get

      result.attachmentId shouldBe testAttachmentId
      result.s3ObjectKey  shouldBe testS3ObjectKey
      result.message      shouldBe testSQSMessageIds.head
    }

    "record processing time" in {

      val metric = CollectorRegistry.defaultRegistry.getSampleValue(
        "attachment_processor_processing_time_sum",
        Array("service"),
        Array(s"/attachment-processor:size=${attachmentSizeBucket(sampleAttachment.length.toLong)}")
      )

      processor.execute.run().request(1).expectNext().toOption.get

      metric               should not be null
      metric.doubleValue() should be > 0.0
    }
  }

  "for failure scenarios attachments processor" should {

    "report a warning when an attachment cannot be downloaded from s3" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink)(using typedSystem, config) {
          override def getMessages: Source[Message, NotUsed]                                                  =
            queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] =
            failure.storageService.downloadAttachment
          override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]     =
            queueService.deleteMessage
        }

      val result = processor.execute
        .run()
        .request(1)
        .expectNext()

      result.isLeft                     shouldBe true
      result.left.toOption.get          shouldBe a[ErrorMessageWithDeleteSQSMessage]
      result.left.toOption.get.severity shouldBe WARN
      result.left.toOption.get.message  shouldBe s"failed to download 738bcba6-7f9e-11ec-8768-3f8498104f38.zip attachment bundle from s3 ${config.attachmentsBucket}"
    }

    "report a warning for signing failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink)(using typedSystem, config) {
          override def getMessages: Source[Message, NotUsed]                                                  = queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] =
            storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]      = failure.signService.signing
        }

      val result = processor.execute
        .run()
        .request(1)
        .expectNext()

      result.isLeft                     shouldBe true
      result.left.toOption.get.severity shouldBe WARN
      result.left.toOption.get.message  shouldBe s"Response status 500 Internal Server Error from signatures service ${config.signaturesServiceHost}"
    }

    "report an error for a glacier failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink)(using typedSystem, config) {
          override def getMessages: Source[Message, NotUsed]                                                     = queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed]    =
            storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]         = signService.signing
          override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] =
            failure.glacierService.archive
        }

      val result = processor.execute.run().request(1).expectNext()

      result.isLeft                                                             shouldBe true
      result.left.toOption.get.severity                                         shouldBe ERROR
      result.left.toOption.get.message.startsWith("Error uploading attachment") shouldBe true
    }

    "report an error for update metastore failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink)(using typedSystem, config) {
          override def getMessages: Source[Message, NotUsed]                                                     = queueService.getMessages
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed]    =
            storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]         = signService.signing
          override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacierService.archive
          override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]  =
            failure.updateService.updateMetastore
        }

      val result = processor.execute.run().request(1).expectNext()

      result.isLeft                     shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message  shouldBe "failure"
    }

    "report an error for parsing SQS message failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink)(using typedSystem, config) {
          override def getMessages: Source[Message, NotUsed]                                              = 
            failure.queueService.getMessages
          override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = 
            queueService.deleteMessage
        }

      val result: EitherErr[AttachmentInfo]                                            = 
        processor.execute.run().request(1).expectNext()

      result.isLeft                     shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get          shouldBe a[ErrorMessageWithDeleteSQSMessage]
      result.left.toOption.get.message    should startWith regex "Parsing SQS message failure"
    }

    "report an error for deleting SQS message failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink)(using typedSystem, config) {
          override def getMessages: Source[Message, NotUsed]                                                     = 
            queueService.getMessages
          override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]        =
            failure.queueService.deleteMessage
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed]    =
            storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]         = 
            signService.signing
          override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = 
            glacierService.archive
          override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]  =
            updateService.updateMetastore
        }

      val result = processor.execute.run().request(1).expectNext()

      result.isLeft                     shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message  shouldBe "Delete SQS message failure"
    }

    "report error for deleting s3 bundle failure" in {
      val processor: ProcessorService[TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
        new ProcessorService(testApplicationSink)(using typedSystem, config) {
          override def getMessages: Source[Message, NotUsed]                                                     = queueService.getMessages
          override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]        = queueService.deleteMessage
          override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed]    =
            storageService.downloadAttachment
          override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]         = signService.signing
          override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacierService.archive
          override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]  =
            updateService.updateMetastore

          override def deleteBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
            failure.storageService.deleteAttachment
        }

      val result = processor.execute.run().request(1).expectNext()

      result.isLeft                     shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
      result.left.toOption.get.message  shouldBe "failure"
    }
  }
}
