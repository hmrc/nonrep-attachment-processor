package uk.gov.hmrc.nonrep.attachment.service

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.Attributes.LogLevels.{Error, Info}
import org.apache.pekko.stream.Attributes.logLevels
import org.apache.pekko.stream.ClosedShape
import org.apache.pekko.stream.scaladsl.RunnableGraph.fromGraph
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.*
import uk.gov.hmrc.nonrep.attachment.app.metrics.Prometheus.{attachmentProcessingDuration, attachmentSizeBucket}
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.duration.{DurationInt, FiniteDuration, NANOSECONDS, SECONDS}

trait Processor[A] {
  def getMessages: Source[Message, NotUsed]

  def parseMessage: Flow[Message, EitherErr[AttachmentInfo], NotUsed]

  def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]

  def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed]

  def unpackBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed]

  def signAttachment: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]

  def repackBundle: Flow[EitherErr[SignedZipContent], EitherErr[AttachmentContent], NotUsed]

  def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed]

  def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]

  def deleteBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]

  def applicationSink: Sink[EitherErr[AttachmentInfo], A]
}

object Processor {
  def apply[A](applicationSink: Sink[EitherErr[AttachmentInfo], A])(using ActorSystem[?], ServiceConfig) =
    ProcessorService(applicationSink)
}

class ProcessorService[A](val applicationSink: Sink[EitherErr[AttachmentInfo], A])(using system: ActorSystem[?], config: ServiceConfig)
    extends Processor[A] {

  val storage: Storage = StorageService()

  val queue: Queue = QueueService()

  val sign: Sign = SignService()

  val bundle: Bundle = BundleService()

  val glacier: Glacier = GlacierService()

  val update: Update = UpdateService()

  override def getMessages: Source[Message, NotUsed] =
    queue.getMessages
      .throttle(config.messagesPerSecond, 1.second)
      .log(name = "getMessages")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def parseMessage: Flow[Message, EitherErr[AttachmentInfo], NotUsed] =
    queue.parseMessages
      .log(name = "parseMessage")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
    queue.deleteMessage
      .log(name = "deleteMessage")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] =
    storage.downloadAttachment
      .log(name = "downloadBundle")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def unpackBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed]       =
    bundle.extractBundle
      .log(name = "unpackBundle")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))
  override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]      =
    sign.signing
      .log(name = "signAttachment")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))
  override def repackBundle: Flow[EitherErr[SignedZipContent], EitherErr[AttachmentContent], NotUsed] =
    bundle.createBundle
      .log(name = "repackBundle")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] =
    glacier.archive
      .log(name = "archiveBundle")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] =
    update.updateMetastore
      .log(name = "updateMetastore")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def deleteBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
    storage.deleteAttachment
      .log(name = "deleteBundle")
      .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  private[service] def recordProcessingTime: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
    Flow[EitherErr[AttachmentInfo]].map {
      _.map { attachment =>
        val processingEnd = System.nanoTime()
        val total         = FiniteDuration(processingEnd - attachment.processingStart, NANOSECONDS)
        val label         =
          attachment.attachmentSize.fold("/attachment-processor")(size => s"/attachment-processor:size=${attachmentSizeBucket(size)}")
        attachmentProcessingDuration.labels(label).observe(total.toUnit(SECONDS))
        attachment
      }
    }

  val execute: RunnableGraph[A] = fromGraph(
    GraphDSL.createGraph(applicationSink)(implicit builder =>
      sink =>
        import GraphDSL.Implicits.*

        getMessages
          ~> parseMessage
          ~> downloadBundle
          ~> unpackBundle
          ~> signAttachment
          ~> repackBundle
          ~> archiveBundle
          ~> updateMetastore
          ~> deleteMessage
          ~> deleteBundle
          ~> recordProcessingTime
          ~> sink

        ClosedShape
    )
  )
}
