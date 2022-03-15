package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.ClosedShape
import akka.stream.scaladsl.RunnableGraph.fromGraph
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

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

  def applicationSink: Sink[EitherErr[AttachmentInfo], A]
}

object Processor {
  def apply[A](applicationSink: Sink[EitherErr[AttachmentInfo], A])
              (implicit system: ActorSystem[_], config: ServiceConfig) = new ProcessorService(applicationSink)
}

class ProcessorService[A](val applicationSink: Sink[EitherErr[AttachmentInfo], A])
                         (implicit val system: ActorSystem[_], config: ServiceConfig)
  extends Processor[A] {

  val storage: Storage = new StorageService()

  val queue: Queue = new QueueService()

  val sign: Sign = new SignService()

  val zip: Zipper = new ZipperService()

  val glacier: Glacier = new GlacierService()

  val update: Update = new UpdateService()

  override def getMessages: Source[Message, NotUsed] = queue.getMessages

  override def parseMessage: Flow[Message, EitherErr[AttachmentInfo], NotUsed] = queue.parseMessages

  override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = queue.deleteMessage

  override def downloadBundle: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = storage.downloadAttachment

  override def unpackBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed] = zip.unzip

  override def repackBundle: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed] = zip.zip

  override def signAttachment: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = sign.signing

  override def archiveBundle: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacier.archive

  override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] = update.updateMetastore

  val execute: RunnableGraph[A] = fromGraph(GraphDSL.createGraph(applicationSink) {
    implicit builder =>
      sink =>
        import GraphDSL.Implicits._

        getMessages ~> parseMessage ~> downloadBundle ~> unpackBundle ~> signAttachment ~> repackBundle ~> archiveBundle ~> updateMetastore ~> deleteMessage ~> sink

        ClosedShape
  })

}
