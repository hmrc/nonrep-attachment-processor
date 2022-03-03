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
  def storage: Storage

  def queue: Queue

  def sign: Sign

  def zip: Zipper

  def glacier: Glacier

  def applicationSink: Sink[EitherErr[ArchivedAttachment], A]
}

object Processor {
  def apply[A](applicationSink: Sink[EitherErr[ArchivedAttachment], A])
              (implicit system: ActorSystem[_], config: ServiceConfig) = new ProcessorService(applicationSink)
}

class ProcessorService[A](val applicationSink: Sink[EitherErr[ArchivedAttachment], A])
                         (implicit val system: ActorSystem[_], config: ServiceConfig)
  extends Processor[A] {

  override lazy val storage: Storage = new StorageService()

  override lazy val queue: Queue = new QueueService()

  override lazy val sign: Sign = new SignService()

  override lazy val zip: Zipper = new ZipperService()

  override lazy val glacier: Glacier = new GlacierService(config.glacierNotificationsSnsTopicArn, config.env)

  val messages: Source[Message, NotUsed] = queue.getMessages

  val parsing: Flow[Message, AttachmentInfo, NotUsed] = queue.parseMessages

  val downloading: Flow[AttachmentInfo, EitherErr[AttachmentContent], NotUsed] = storage.downloadAttachment()

  val unpacking: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed] = zip.unzip()

  val repacking: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed] = zip.zip()

  val signing: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = sign.signing()

  val archiving: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] = glacier.archive

  val execute: RunnableGraph[A] = fromGraph(GraphDSL.createGraph(applicationSink) {
    implicit builder =>
      sink =>
        import GraphDSL.Implicits._

        messages ~> parsing ~> downloading ~> unpacking ~> signing ~> repacking ~> archiving ~> sink

        ClosedShape
  })

}
