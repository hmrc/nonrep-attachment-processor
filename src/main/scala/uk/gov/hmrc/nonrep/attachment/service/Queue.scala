package uk.gov.hmrc.nonrep.attachment
package service

import software.amazon.awssdk.regions.Region.EU_WEST_2
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, ReceiveMessageRequest}
import uk.gov.hmrc.nonrep.attachment
import uk.gov.hmrc.nonrep.attachment.model.{ProcessingBundle, SQSMessage}
import uk.gov.hmrc.nonrep.attachment.utils.JSONUtils

import scala.jdk.CollectionConverters._
import scala.util.Try

trait Queue {
  self: JSONUtils =>

  def sqsReadClient: SqsClient

  def sqsDeleteClient: SqsClient

  class QueueService extends Queue {

    override def readQueue: ReadQueueMessages = (sqs, limit) =>
      Try {
        val response =
          sqsReadClient.receiveMessage(ReceiveMessageRequest.builder().queueUrl(sqs).maxNumberOfMessages(limit).build())
        response.messages.asScala.map(m => SQSMessage(m.receiptHandle, m.body)).toList
      }.toEither.left.map {
        case thr: Exception =>
          attachment.ErrorMessage(s"Error: ${thr.getMessage} while reading SQS queue: $sqs", WARN)
      }

    override def deleteMessage: DeleteMessageFromQueue = (sqs, handle) =>
      Try {
        sqsDeleteClient.deleteMessage(DeleteMessageRequest.builder().queueUrl(sqs).receiptHandle(handle).build()).toString
      }.toEither.left.map {
        case thr: Exception =>
          ErrorMessage(s"Error: ${thr.getMessage} while deleting message: $handle from SQS queue: $sqs", WARN)
      }

     def parseSQSMessage: SQSMessageParser = (handle, json) =>
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
          case _ => None ////get rid of s3:TestEvent
        }
      }.fold(_ => None, f => f)
  }

  object QueueService extends Queue with JSONUtils {
    lazy val sqsReadClient: SqsClient = buildSqsClient()
    lazy val sqsDeleteClient: SqsClient = buildSqsClient()

    private def buildSqsClient() = SqsClient.builder().region(EU_WEST_2).build()
  }
}
