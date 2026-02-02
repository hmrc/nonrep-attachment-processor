package uk.gov.hmrc.nonrep.attachment.service

import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.Supervision.restartingDecider
import org.apache.pekko.stream.connectors.sqs.scaladsl.SqsSource
import org.apache.pekko.stream.connectors.sqs.SqsSourceSettings
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.util.Timeout
import org.apache.pekko.{Done, NotUsed}
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region.EU_WEST_2
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, Message}
import spray.json.DefaultJsonProtocol.*
import spray.json.*
import uk.gov.hmrc.nonrep.attachment.*
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.utils.ErrorHandler

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.FutureConverters.CompletionStageOps
import scala.util.Try

trait Queue {
  def settings: SqsSourceSettings

  def getMessages: Source[Message, NotUsed]

  def parseMessages: Flow[Message, EitherErr[AttachmentInfoMessage], NotUsed]

  def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]
}

class QueueService()(using val config: ServiceConfig, system: ActorSystem[?]) extends Queue with ErrorHandler {

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

  override val settings: SqsSourceSettings =
    SqsSourceSettings()
      .withMaxBufferSize(config.maxBufferSize)
      .withMaxBatchSize(config.maxBatchSize)
      .withCloseOnEmptyReceive(config.closeOnEmptyReceive)
      .withWaitTimeSeconds(config.waitTimeSeconds)

  override def getMessages: Source[Message, NotUsed] =
    SqsSource(config.queueUrl, settings)

  override def parseMessages: Flow[Message, EitherErr[AttachmentInfoMessage], NotUsed] =
    Flow[Message].map { message =>
      val messageHandle = message.receiptHandle()

      Try {
        val s3ObjectKey = message
          .body()
          .parseJson
          .asJsObject
          .fields("Records")
          .convertTo[List[JsValue]]
          .head
          .asJsObject
          .fields("s3")
          .asJsObject
          .fields("object")
          .asJsObject
          .fields("key")
          .convertTo[String]

        val attachmentId = s3ObjectKey
          .replaceFirst(".zip", "")
        AttachmentInfoMessage(attachmentId, messageHandle, s3ObjectKey)
      }.toEither.left.map(thr =>
        ErrorMessageWithDeleteSQSMessage(messageHandle, s"Parsing SQS message failure ${message.body()}", Some(thr))
      )
    }

  override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] = {
    def delete(receiptHandle: String) = {
      val request = DeleteMessageRequest.builder().queueUrl(config.queueUrl).receiptHandle(receiptHandle).build()
      client.deleteMessage(request).asScala
    }

    Flow[EitherErr[AttachmentInfo]]
      .mapAsyncUnordered(8) {
        case Right(info)                                   =>
          delete(info.message).map(_ => Right(info))
        case Left(error: ErrorMessageWithDeleteSQSMessage) =>
          system.log.error(s"failure caused by: ${error.message}, SQS message to be removed")
          delete(error.messageId).map(_ => Left(error))
        case Left(error)                                   =>
          Future.successful(Left(error))
      }
      .withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))
  }
}
