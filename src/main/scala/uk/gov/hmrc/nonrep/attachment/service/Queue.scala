package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.alpakka.sqs.scaladsl.SqsSource
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.scaladsl.{Flow, RestartSource, Sink, Source}
import software.amazon.awssdk.services.s3.model.Event
import software.amazon.awssdk.services.sqs.model.Message

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

/**
 * It's an interim object before the final interface is delivered
 */

trait Queue [A] {

  def getMessages:Source[Message, NotUsed]

  def parseMessages: Flow[Message, AttachmentInfo, NotUsed]

  def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed]
}

class QueueService extends Queue {

  private def getMessages: Source[Message, NotUsed] = {
    val supervisionStrategy: Supervision.Decider = Supervision.stoppingDecider
    val sourceSettings = SqsSourceSettings()
      .withWaitTime(10 seconds)
      .withMaxBufferSize(150)
      .withMaxBatchSize(10)
    val sqsSource = SqsSource(
      sqsTopicArn,
      sourceSettings
    ).withAttributes(ActorAttributes.supervisionStrategy(supervisionStrategy))

    RestartSource.onFailuresWithBackoff(
      minBackoff = 300.millis,
      maxBackoff = 5.seconds,
      randomFactor = 0.25
    )(() => sqsSource)
  }

  private def getFlow: Flow[Message, AttachmentInfo, NotUsed] = {

    def processMessage(message: Message): Future[Event] = {
      val event = Json.parse(message.body()).as[Event]
      println(s"Processing event => $event")
      Future.successful(event)
    }

    Flow[Message].mapAsyncUnordered(8) {
      message =>
        for {
          _ <- processMessage(message)
          _ <- Future.successful(MessageAction.delete(message))
        } yield ()
    }
  }

  getMessages
    .via(getFlow)
    .runWith(Sink.ignore)

  // make sure that SQS async client is created with important parameters taken from service config

  /*
   * all these implementations are likely to be replaced
   */
  override def getMessages: Source[Message, NotUsed] = Source.empty

  override def parseMessages: Flow[Message, AttachmentInfo, NotUsed] = Flow.fromFunction((m: Message) => AttachmentInfo(m.messageId(), ""))

  override def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] = Flow.fromFunction((_: AttachmentInfo) => true)

}
