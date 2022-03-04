package uk.gov.hmrc.nonrep.attachment
package service


import akka.NotUsed
import akka.http.DefaultParsingErrorHandler.handle
import akka.http.scaladsl.common.EntityStreamingSupport.json
import akka.stream.scaladsl.{Flow, Source}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, Message, ReceiveMessageRequest}

import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.language.postfixOps
import scala.util.Try



/**
 * It's an interim object before the final interface is delivered
 */

class Queue extends Queue {
  private lazy val client: SqsClient = SqsClient.builder().region(Region.EU_WEST_2).build()

  private val sqsTopicArn = "sqsTopicArn"

  // make sure that SQS async client is created with important parameters taken from service config

  /*
   * all these implementations are likely to be replaced
   */
   def getMessages: Source[Message, NotUsed] = Source.empty

   def parseMessages: Flow[Message, AttachmentInfo, NotUsed] = Flow.fromFunction((m: Message) => AttachmentInfo(m.messageId(), ""))

   def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] = Flow.fromFunction((_: AttachmentInfo) => true)

  private [service] def readQueue(): Either[ErrorMessage, Seq[Message]] =
    Try {
      val response = client.receiveMessage(ReceiveMessageRequest.builder()
        .queueUrl("sqsTopicArn")
        .waitTimeSeconds(20)
        .maxNumberOfMessages(100)
        .build())
      response.messages.asScala.toSeq
    }.toEither.left.map {
      case thr: Exception =>
        ErrorMessage(s"Error: ${thr.getMessage} while reading SQS queue: sqsTopicArn", WARN)
    }


  private [service] def parseMessage(): EitherErr[AttachmentInfo] =
    Try {
      val messages = jsonStringToMap(json)
      messages.get("Records") match {
        case Some(records: List[Any]) =>
          val s3 = records.map(_.asInstanceOf[Map[String, Any]]).map(_ ("s3").asInstanceOf[Map[String, Any]])
          val keys = s3.map(_ ("object").asInstanceOf[Map[String, Any]]).map(_ ("key").toString())
          val buckets = s3.map(_ ("bucket").asInstanceOf[Map[String, Any]]).map(_ ("name").toString())
          Some(buckets.zip(keys).map {
            case (bucket, key) => ProcessingBundle(handle, bucket, key)
          })
        case _ => None
      }
    }.fold(_ => None, f => f)

  private [service] def deleteMessage(messageReceiptHandle: String): EitherErr[Boolean] =
    Try {
      client
        .deleteMessage(
          DeleteMessageRequest.builder().queueUrl("sqsTopicArn").receiptHandle(messageReceiptHandle).build())
        .sdkHttpResponse()
        .isSuccessful
    }.toEither.left.map {
      case thr: Exception =>
        ErrorMessage(s"Error: ${thr.getMessage} while deleting message: $messageReceiptHandle from SQS queue: sqsTopicArn", WARN)
    }
}
