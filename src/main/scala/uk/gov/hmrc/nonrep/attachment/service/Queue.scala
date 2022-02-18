package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.alpakka.sqs.scaladsl.{SqsPublishFlow, SqsSource}
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.scaladsl.Source.single
import akka.stream.scaladsl.{Flow, RestartSource, Sink, Source}
import com.github.matsluni.akkahttpspi.AkkaHttpClient
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{Message, SendMessageRequest}
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.collection.immutable
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

//  def sqsDeleteClient: AmazonSQS
}

class QueueService()(implicit val config: ServiceConfig,
                     implicit val system: ActorSystem[_]) extends Queue {

  implicit val asyncClient = SqsAsyncClient.builder().credentialsProvider(AwsCredentialsProvider).region(Region.EU_WEST_2).build()
//    .httpClient(AkkaHttpClient.builder().withActorSystem(system).build())

  val supervisionStrategy: Supervision.Decider = Supervision.stoppingDecider
  val sourceSettings = SqsSourceSettings()
    .withWaitTime(10 seconds)
    .withMaxBufferSize(150)
    .withMaxBatchSize(10)

  def processMessage:SQSMessageParser = (attachmentInfo, json) => { //read from sqs queue
      Source {
        val messages: Future[immutable.Seq[Message]] =
          SqsSource(
            queueUrl,
            SqsSourceSettings().withCloseOnEmptyReceive(true).withWaitTime(20.millis)
          ).runWith(Sink.seq)
      }
      Flow[Message].map {
        Source
        .single(SendMessageRequest.builder().messageBody("ATTACHMENT_SQS").build())
          .via(Flow(queueUrl("")))
          .runWith(Sink.head)
      }
    }



  // make sure that SQS async client is created with important parameters taken from service config

  /*
   * all these implementations are likely to be replaced
   */
  override def getMessages: Source[Message, NotUsed] = SqsSource(config.sqsTopicArn, sourceSettings)

  override def parseMessages: Flow[Message, AttachmentInfo, NotUsed] = Flow.fromFunction((m: Message) => AttachmentInfo(m.messageId(), ""))
    .withAttributes(ActorAttributes.supervisionStrategy(supervisionStrategy))

  override def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] = Flow.fromFunction((_: AttachmentInfo) => true)

}

