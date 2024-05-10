package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.Attributes.LogLevels.{Error, Info}
import akka.stream.Attributes.logLevels
import akka.stream.ClosedShape
import akka.stream.scaladsl.RunnableGraph.fromGraph
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.duration.DurationInt

trait Processor[A] {
  def getMessages: Source[Message, NotUsed]

  def parseMessage: Flow[Message, EitherErr[AttachmentInfo], NotUsed]

  def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]

  def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed]

  def unpackBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed]

  def repackBundle: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed]

  def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed]

  def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed]

  def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]

  def deleteBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]

  def applicationSink: Sink[EitherErr[AttachmentInfo], A]
}

object Processor {
  def apply[A](applicationSink: Sink[EitherErr[AttachmentInfo], A])
              (implicit system: ActorSystem[_], config: ServiceConfig) = new ProcessorService(applicationSink)
}

class ProcessorService[A](val applicationSink: Sink[EitherErr[AttachmentInfo], A])
                         (implicit val system: ActorSystem[_], config: ServiceConfig)
  extends Processor[A] {

  val queue: Queue = new QueueService()

  val storage: Storage = new StorageService(queue)

  val sign: Sign = new SignService()

  val bundle: Bundle = new BundleService()

  val glacier: Glacier = new GlacierService()

  val update: Update = new UpdateService()

  override def getMessages: Source[Message, NotUsed] = queue.getMessages.throttle(config.messagesPerSecond, 1.second)
    .log(name = "getMessages")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def parseMessage: Flow[Message, EitherErr[AttachmentInfo], NotUsed] = queue.parseMessages
    .log(name = "parseMessage")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = queue.deleteMessage
    .log(name = "deleteMessage")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storage.downloadAttachment
    .log(name = "downloadBundle")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def unpackBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed] = bundle.extractBundle
    .log(name = "unpackBundle")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def repackBundle: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed] = bundle.createBundle
    .log(name = "repackBundle")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = sign.signing
    .log(name = "signAttachment")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacier.archive
    .log(name = "archiveBundle")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] = update.updateMetastore
    .log(name = "updateMetastore")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  override def deleteBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = storage.deleteAttachment
    .log(name = "deleteBundle")
    .addAttributes(logLevels(onElement = Info, onFinish = Info, onFailure = Error))

  val execute: RunnableGraph[A] = fromGraph(GraphDSL.createGraph(applicationSink) {
    implicit builder =>
      sink =>
        import GraphDSL.Implicits._

        getMessages ~> parseMessage ~> downloadBundle ~> unpackBundle ~> signAttachment ~> repackBundle ~> archiveBundle ~> updateMetastore ~> deleteMessage ~> deleteBundle ~> sink

        ClosedShape
  })

}
