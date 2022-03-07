package uk.gov.hmrc.nonrep.attachment
package service

import akka.Done
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.{SqsAckFlow, SqsAckSink, SqsSource}
import akka.stream.scaladsl.Sink
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.{spy, verify}
import software.amazon.awssdk.regions.Region.EU_WEST_2
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, SendMessageRequest}
import uk.gov.hmrc.nonrep.attachment.BaseSpec
import uk.gov.hmrc.nonrep.attachment.TestServices.config.env
import uk.gov.hmrc.nonrep.attachment.TestServices.success.queueService.getMessages

import scala.concurrent.duration.DurationInt

class QueueSpec extends BaseSpec {

  import TestServices._

 val queue = new QueueService
  val queueUrl: String = s"https://sqs.eu-west-2.amazonaws.com/205520045207/$env-nonrep-attachment-queue"
  implicit val awsSqsClient: SqsAsyncClient = spy(SqsAsyncClient.builder().region(EU_WEST_2).build())

  def sendMessage(message: String)

  val sqsSourceSettings: SqsSourceSettings = ???

  "Queue service" should {
    "SqsSourceSettings should have" in {
      val settings = SqsSourceSettings()
        .withWaitTime(10.seconds)
        .withMaxBufferSize(100)
        .withMaxBatchSize(10)
        .withWaitTimeSeconds(10)
        .withCloseOnEmptyReceive(true)

      settings.maxBufferSize should be(100)
    }

  "get message from queue using queue url" in {

    val future: Any = SqsSource(queueUrl, sqsSourceSettings: SqsSourceSettings)
      .take(1)
      .via(SqsAckFlow(queueUrl))
      .runWith(Sink.head)
      .build()
  }
      }

  "parse message properly" in {

  }

  "deleting message from queue threw runtime exception" in {
    sendMessage("sqs message")
    val future = SqsSource(queueUrl, sqsSourceSettings)
      .take(1)
      .map(MessageAction.Delete(_))
      .runWith(SqsAckSink(queueUrl))

    future.futureValue shouldBe Done
    verify(awsSqsClient).deleteMessage(
      any[DeleteMessageRequest])
    }
}
