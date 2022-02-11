package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.ClosedShape
import akka.stream.scaladsl.{Flow, GraphDSL, RunnableGraph, Sink, Source}
import software.amazon.awssdk.services.sqs.model.Message

import scala.concurrent.ExecutionContext

object Processor {
  def apply()(implicit system: ActorSystem[_],
              ec: ExecutionContext,
              storage: Storage[AttachmentInfo]) = new Processor()
}

class Processor()(implicit val system: ActorSystem[_],
                  ec: ExecutionContext,
                  storage: Storage[AttachmentInfo]) {


  val sqsMessages: Source[Message, NotUsed] = Queue.getMessages

  val parseMessages: Flow[Message, AttachmentInfo, NotUsed] = Queue.parseMessages

  val downloadFromS3: Flow[AttachmentInfo, EitherErr[AttachmentInfo], NotUsed] = storage.downloadAttachment

  val applicationSink = Sink.seq[EitherErr[AttachmentInfo]]

  val execute = RunnableGraph.fromGraph(GraphDSL.createGraph(applicationSink) {
    implicit builder =>
      sink =>
        import GraphDSL.Implicits._

        sqsMessages ~> parseMessages ~> downloadFromS3 ~> sink

        ClosedShape
  })


}
