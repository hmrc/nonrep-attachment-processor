package uk.gov.hmrc.nonrep.attachment
package service

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.stream.ActorAttributes
import akka.stream.Supervision.stoppingDecider
import akka.stream.alpakka.sqs.SqsSourceSettings
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

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.Try

trait Queue {
  def settings: SqsSourceSettings

  def getMessages: Source[Message, NotUsed]

  def parseMessages: Flow[Message, EitherErr[AttachmentInfo], NotUsed]

  def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]
}

class QueueService()(implicit val config: ServiceConfig,
                     implicit val system: ActorSystem[_]) extends Queue {

  implicit val ec: ExecutionContextExecutor = system.executionContext

  private[service] implicit lazy val client: SqsAsyncClient = SqsAsyncClient.builder().region(EU_WEST_2).build()

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "close SQS client") { () =>
    implicit val timeout: Timeout = Timeout(5.seconds)
    Future {
      client.close()
      Done
    }
  }

  override val settings: SqsSourceSettings = SqsSourceSettings()
    .withMaxBufferSize(config.maxBufferSize)
    .withMaxBatchSize(config.maxBatchSize)
    .withCloseOnEmptyReceive(config.closeOnEmptyReceive)
    .withWaitTimeSeconds(config.waitTimeSeconds)

  override def getMessages: Source[Message, NotUsed] = SqsSource(config.queueUrl, settings)

  override def parseMessages: Flow[Message, EitherErr[AttachmentInfo], NotUsed] =
    Flow[Message].map { message =>
      val messageId = message.messageId()

      Try {
        val attachmentId = message
          .body()
          .parseJson
          .asJsObject.fields("Records").convertTo[List[JsValue]].head
          .asJsObject.fields("s3")
          .asJsObject.fields("object")
          .asJsObject.fields("key").convertTo[String]
          .replaceFirst(".zip", "")
        AttachmentInfo(messageId, attachmentId)
      }.toEither.left.map(thr => {
        system.log.error(s"Parsing SQS message failure ${message.body()}", thr)
        ErrorMessage("Parsing SQS message failure")
      })
    }

  override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
    Flow[EitherErr[AttachmentInfo]].mapAsyncUnordered(8) { attachmentInfo =>
      attachmentInfo.fold(
        error => Future.successful(Left(error)),
        info => {
          val request = DeleteMessageRequest.builder().queueUrl(config.queueUrl).receiptHandle(info.message).build()
          client.deleteMessage(request).asScala.map(_ => Right(info))
        })
    }.withAttributes(ActorAttributes.supervisionStrategy(stoppingDecider))

}
