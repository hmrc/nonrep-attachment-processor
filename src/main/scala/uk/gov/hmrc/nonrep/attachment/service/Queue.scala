package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonEntityStreamingSupport.json
import akka.stream.{ActorAttributes, Supervision}
import akka.stream.alpakka.sqs.scaladsl.SqsSource
import akka.stream.alpakka.sqs.SqsSourceSettings
import akka.stream.scaladsl.{Flow, Source}
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Try

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
    .withWaitTime(config.waitTime seconds)
    .withMaxBufferSize(config.maxBufferSize)
    .withMaxBatchSize(config.maxBatchSize)

  // make sure that SQS async client is created with important parameters taken from service config

  /*
   * all these implementations are likely to be replaced
   */
  override def getMessages: Source[Message, NotUsed] = SqsSource(config.sqsTopicArn, sourceSettings)

  override def parseMessages: Flow[Message, AttachmentInfo, NotUsed] = Flow[Message].map { message =>
    Try {
      val message = jsonStringToMap(json)
      message.get("Records") match {
        case Some(records: List[Any]) =>
          val s3 = records.map(_.asInstanceOf[Map[String, Any]]).map(_ ("s3").asInstanceOf[Map[String, Any]])
          val keys = s3.map(_ ("object").asInstanceOf[Map[String, Any]]).map(_ ("key").toString())
          val buckets = s3.map(_ ("bucket").asInstanceOf[Map[String, Any]]).map(_ ("name").toString())
          Some(buckets.zip(keys).map {
            case(_, _) => Flow(AttachmentInfo(message,(),"s3"))
          })
        case _ => None
      }
    }.fold(_ => None, f => f)
    //possibly here you'll have parsing message.body as Json
    AttachmentInfo(message.messageId(),"key"/* here you have to extract s3 object key from message body*/)
  } ((m: Message) => AttachmentInfo(m.messageId(), "s3"))
    .withAttributes(ActorAttributes.supervisionStrategy(supervisionStrategy))

  override def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] = Flow.fromFunction((_: AttachmentInfo) => true)

}

