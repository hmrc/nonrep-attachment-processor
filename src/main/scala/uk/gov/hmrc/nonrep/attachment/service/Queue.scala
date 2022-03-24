package uk.gov.hmrc.nonrep.attachment
package service

import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.stream.Supervision.stoppingDecider
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.alpakka.sqs.scaladsl.SqsSource
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Partition, Sink, Source, Zip}
import akka.stream.{ActorAttributes, FlowShape}
import akka.util.Timeout
import akka.{Done, NotUsed}
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
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

  private def partitionRequests[A]() =
    Partition[EitherErr[A]](2, {
      case Left(_) => 0
      case Right(_) => 1
    })

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

  private[service] def parseMessage: Flow[Message, EitherErr[AttachmentInfo], NotUsed] =
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
        system.log.error(s"Parsing SQS message failure ${message.body()}", thr)
        ErrorMessage("Parsing SQS message failure")
      })
    }

  override def parseMessages: Flow[Message, EitherErr[AttachmentInfo], NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val broadcastMessageShape = builder.add(Broadcast[Message](2))
        val partition = builder.add(partitionRequests[AttachmentInfo]())
        val zip = builder.add(Zip[Message, EitherErr[AttachmentInfo]]())
        val broadcastAttachmentInfo = builder.add(Broadcast[EitherErr[AttachmentInfo]](2))
        val merge = builder.add(Merge[EitherErr[AttachmentInfo]](2, true))

        broadcastMessageShape ~> parseMessage ~> partition

        broadcastMessageShape ~> zip.in0
        partition ~> broadcastAttachmentInfo

        broadcastAttachmentInfo ~> zip.in1

        broadcastAttachmentInfo ~> merge
        partition ~> merge

        zip.out.map {
          case (message, _) => Right(AttachmentInfo(message.receiptHandle(), message.messageId()))
        } ~> deleteMessage ~> Sink.foreach[EitherErr[AttachmentInfo]](info => system.log.info(s"Invalid SQS message removed $info"))

        FlowShape(broadcastMessageShape.in, merge.out)
      })

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
