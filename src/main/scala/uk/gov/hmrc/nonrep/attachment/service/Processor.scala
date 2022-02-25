package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.ClosedShape
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

trait Processor[A] {
  def storage: Storage

  def zipper: Zipper

  def queue: Queue

  def sign: Sign

  def zip: Zipper

  def applicationSink: Sink[EitherErr[AttachmentContent], A]
}

object Processor {
  def apply[A](applicationSink: Sink[EitherErr[AttachmentContent], A])(implicit system: ActorSystem[_],
                                                                       config: ServiceConfig) = new ProcessorService(applicationSink)
}

class ProcessorService[A](val applicationSink: Sink[EitherErr[AttachmentContent], A])(implicit val system: ActorSystem[_],
                                                                                      config: ServiceConfig) extends Processor[A] {

  override def storage: Storage = new StorageService()

  override def zipper: Zipper = new ZipperService()

  override def queue: Queue = new QueueService()

  override def sign: Sign = new SignService()

  override def zip: Zipper = new ZipperService()

  val messages: Source[Message, NotUsed] = queue.readQueueMessages

  val parsing: Flow[Message, AttachmentInfo, NotUsed] = queue.parseSQSMessages

  val downloading: Flow[AttachmentInfo, EitherErr[AttachmentContent], NotUsed] = storage.downloadAttachment()

  val unpacking: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed] = zip.unzip()

  val repacking: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed] = zip.zip()

  val signing: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] = sign.signing()

  val execute = RunnableGraph.fromGraph(GraphDSL.createGraph(applicationSink) {
    implicit builder =>
      sink =>
        import GraphDSL.Implicits._

        messages ~> parsing ~> downloading ~> unpacking ~> signing ~> repacking ~> sink

        ClosedShape
  })

}
