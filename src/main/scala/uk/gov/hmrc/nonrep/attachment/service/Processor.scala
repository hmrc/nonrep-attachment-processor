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

  override def sign: Sign = Sign

  val sqsMessages: Source[Message, NotUsed] = queue.getMessages

  val parseMessages: Flow[Message, AttachmentInfo, NotUsed] = queue.parseMessages

  val downloadFromS3: Flow[AttachmentInfo, EitherErr[AttachmentContent], NotUsed] = storage.downloadAttachment()

  val execute = RunnableGraph.fromGraph(GraphDSL.createGraph(applicationSink) {
    implicit builder =>
      sink =>

        import GraphDSL.Implicits._

        sqsMessages ~> parseMessages ~> downloadFromS3 ~> sink

        ClosedShape
  })


}
