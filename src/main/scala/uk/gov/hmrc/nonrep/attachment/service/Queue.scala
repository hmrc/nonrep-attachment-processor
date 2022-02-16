package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.alpakka.sqs.scaladsl.SqsSource
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.scaladsl.{Flow, RestartSource, Sink, Source}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.model.Event
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

/**
 * It's an interim object before the final interface is delivered
 */

trait Queue {

  def getMessages:Source[Message, NotUsed]

  def parseMessages: Flow[Message, AttachmentInfo, NotUsed]

  def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed]
}

class QueueService()(implicit val config: ServiceConfig,
                     implicit val system: ActorSystem[_]) extends Queue {

  implicit val asyncClient = SqsAsyncClient.builder().region(Region.EU_WEST_2).build()

  val supervisionStrategy: Supervision.Decider = Supervision.stoppingDecider
  val sourceSettings = SqsSourceSettings()
    .withWaitTime(10 seconds)
    .withMaxBufferSize(150)
    .withMaxBatchSize(10)
  private def getMessages: Source[Message, NotUsed] = {
    RestartSource.onFailuresWithBackoff(
      minBackoff = 300.millis,
      maxBackoff = 5.seconds,
      randomFactor = 0.25
    )(() => sqsSource)
  }
    def processMessage(message: Message): Future[Event] = {
      val event = Json.parse(message.body()).as[Event]
    }
    Flow[Message].map{message => message.}


  // make sure that SQS async client is created with important parameters taken from service config

  /*
   * all these implementations are likely to be replaced
   */
  override def getMessages: Source[Message, NotUsed] = SqsSource(config.sqsTopicArn, sourceSettings)

  override def parseMessages: Flow[Message, AttachmentInfo, NotUsed] = Flow.fromFunction((m: Message) => AttachmentInfo(m.messageId(), ""))
    .withAttributes(ActorAttributes.supervisionStrategy(supervisionStrategy))

  override def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] = Flow.fromFunction((_: AttachmentInfo) => true)

}
