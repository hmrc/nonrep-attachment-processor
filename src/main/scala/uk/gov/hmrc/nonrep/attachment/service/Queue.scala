package uk.gov.hmrc.nonrep.attachment
package service

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.stream.ActorAttributes
import akka.stream.Supervision.restartingDecider
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.alpakka.sqs.scaladsl.SqsSource
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import akka.{Done, NotUsed}
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region.EU_WEST_2
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, Message}
import spray.json.DefaultJsonProtocol._
import spray.json._
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.utils.ErrorHandler

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.Try

trait Queue {
  def settings: SqsSourceSettings

  def getMessages: Source[Message, NotUsed]

  def parseMessages: Flow[Message, EitherErr[AttachmentInfo], NotUsed]

  def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]

  def invalidMessage[A](convertF: A => AttachmentInfo): Sink[EitherErr[A], NotUsed]
}

class QueueService()(implicit val config: ServiceConfig,
                     implicit val system: ActorSystem[_]) extends Queue with ErrorHandler {

  implicit val ec: ExecutionContextExecutor = system.executionContext

  private[service] implicit lazy val client: SqsAsyncClient = SqsAsyncClient
    .builder()
    .region(EU_WEST_2)
    .httpClientBuilder(NettyNioAsyncHttpClient.builder())
    .build()

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

  override def invalidMessage[A](convertF: A => AttachmentInfo): Sink[EitherErr[A], NotUsed] =
    Flow[EitherErr[A]]
      .map(_.map(convertF(_)))
      .map {
        case Right(info) =>
          system.log.error("incorrectly called invalid message")
          Right(info)
        case Left(ErrorMessageWithDeleteSQSMessage(messageId, message, None, WARN)) =>
          system.log.warn(message)
          Right(AttachmentInfo(messageId, message))
        case Left(ErrorMessageWithDeleteSQSMessage(messageId, message, Some(throwable), WARN)) =>
          system.log.warn(message, throwable)
          Right(AttachmentInfo(messageId, message))
        case Left(ErrorMessageWithDeleteSQSMessage(messageId, message, None, ERROR)) =>
          system.log.error(message)
          Right(AttachmentInfo(messageId, message))
        case Left(ErrorMessageWithDeleteSQSMessage(messageId, message, Some(throwable), ERROR)) =>
          system.log.error(message, throwable)
          Right(AttachmentInfo(messageId, message))
        case Left(error) =>
          system.log.error("incorrectly called invalid message")
          Left(error)
      }
      .via(deleteMessage)
      .to(Sink.foreach[EitherErr[AttachmentInfo]]{
        case Right(_) => system.log.error("Invalid SQS message removed")
        case Left(error) =>
          system.log.error(s"delete message failed due to ${error.message}")
          errorHandler(system)(error)
      })

  override def parseMessages: Flow[Message, EitherErr[AttachmentInfo], NotUsed] =
    Flow[Message].map { message =>
      val messageHandle = message.receiptHandle()

      Try {
        val attachmentId = message
          .body()
          .parseJson
          .asJsObject.fields("Records").convertTo[List[JsValue]].head
          .asJsObject.fields("s3")
          .asJsObject.fields("object")
          .asJsObject.fields("key").convertTo[String]
          .replaceFirst(".zip", "")
        AttachmentInfo(messageHandle, attachmentId)
      }.toEither.left.map(thr => {
        ErrorMessageWithDeleteSQSMessage(messageHandle, s"Parsing SQS message failure ${message.body()}", Some(thr))
      })
    }.divertTo(invalidMessage[AttachmentInfo](identity), _.isLeft)

  override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
    Flow[EitherErr[AttachmentInfo]].mapAsyncUnordered(8) {
      case Left(error) => Future.successful(Left(error))
      case Right(info) =>
        val request = DeleteMessageRequest.builder().queueUrl(config.queueUrl).receiptHandle(info.message).build()
        client.deleteMessage(request).asScala.map(_ => Right(info))
    }.withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))
}
