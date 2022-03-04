package uk.gov.hmrc.nonrep.attachment
package service

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.stream.ActorAttributes
import akka.stream.Supervision.stoppingDecider
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.SqsSource
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import akka.{Done, NotUsed}
import software.amazon.awssdk.regions.Region.EU_WEST_2
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, Message}
import spray.json.DefaultJsonProtocol._
import spray.json._
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.jdk.FutureConverters.CompletionStageOps

trait Queue {
  def getMessages: Source[Message, NotUsed]

  def parseMessages: Flow[Message, AttachmentInfo, NotUsed]

  def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed]
}

class QueueService()(implicit val config: ServiceConfig,
                     implicit val system: ActorSystem[_]) extends Queue {

  implicit val ec = system.executionContext

  private [service] implicit lazy val client: SqsAsyncClient = SqsAsyncClient.builder().region(EU_WEST_2).build()

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "close SQS client") { () =>
    implicit val timeout = Timeout(5.seconds)
    Future {
      client.close()
      Done
    }
  }

  val settings = SqsSourceSettings()
    .withWaitTime(10.seconds)
    .withMaxBufferSize(10)
    .withMaxBatchSize(10)
    .withCloseOnEmptyReceive(false)
    .withWaitTimeSeconds(20)

  override def getMessages: Source[Message, NotUsed] = SqsSource(config.queueUrl, settings)

  override def parseMessages: Flow[Message, AttachmentInfo, NotUsed] =
    Flow[Message].map { message =>
      val messageId = message.messageId()

      val attachmentId = message
        .body()
        .parseJson
        .asJsObject.fields("Records").convertTo[List[JsValue]].head
        .asJsObject.fields("s3")
        .asJsObject.fields("object")
        .asJsObject.fields("key").convertTo[String]

      AttachmentInfo(messageId, attachmentId)
    }

  override def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] =
    Flow[AttachmentInfo].mapAsyncUnordered(8){ info =>
      val request = DeleteMessageRequest.builder().queueUrl(config.queueUrl).receiptHandle(info.message).build()
      client.deleteMessage(request).asScala.map(_ => true)
    }.withAttributes(ActorAttributes.supervisionStrategy(stoppingDecider))

}
