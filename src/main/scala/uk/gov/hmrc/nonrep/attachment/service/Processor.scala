package uk.gov.hmrc.nonrep.attachment
package service

import akka.actor.typed.ActorSystem
import akka.stream.ClosedShape
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink}

import scala.concurrent.ExecutionContext

class Processor()(implicit val system: ActorSystem[_], val ec: ExecutionContext) {

  val sqsMessages = Queue.getMessages

  val parseMessages = Queue.parseMessages

  val applicationSink = Sink.seq[AttachmentInfo]

  val execute = RunnableGraph.fromGraph(GraphDSL.createGraph(applicationSink) {
    implicit builder => sink =>
    import GraphDSL.Implicits._

    sqsMessages ~> parseMessages ~> sink

    ClosedShape
  })



}
