package uk.gov.hmrc.nonrep.attachment
package service

import akka.Done
import akka.stream.alpakka.sqs.MessageAction
import akka.stream.alpakka.sqs.SqsAckResult.SqsDeleteResult
import akka.stream.alpakka.sqs.SqsAckResultEntry.SqsDeleteResultEntry
import akka.stream.alpakka.sqs.scaladsl.{SqsAckSink, SqsSource}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.testkit.scaladsl.TestSink
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, times, verify, when}
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, DeleteMessageResponse, Message}
import uk.gov.hmrc.nonrep.attachment.BaseSpec
import uk.gov.hmrc.nonrep.attachment.TestServices.config.queueUrl

import scala.compat.java8.FutureConverters.CompletionStageOps

class QueueSpec extends BaseSpec {

  import TestServices._

  "Queue service" should {
    import TestServices.success._
    "SqsSourceSettings should have" in {
      queueService.settings.maxBatchSize shouldBe config.maxBatchSize
      queueService.settings.maxBufferSize shouldBe config.maxBufferSize
      queueService.settings.waitTimeSeconds shouldBe config.waitTimeSeconds
      queueService.settings.closeOnEmptyReceive shouldBe config.closeOnEmptyReceive
    }

    "Message from source should have" in {
      val sink = TestSink.probe[Message]

      val sub = queueService.getMessages.runWith(sink)
      val result = sub
        .request(1)
        .expectNext()

      testSQSMessageIds should contain(result.messageId())
    }

    "Parse message properly" in {
      val sink = TestSink.probe[EitherErr[AttachmentInfo]]

      val (_, sub) = queueService
        .getMessages
        .via(queueService.parseMessages)
        .toMat(sink)(Keep.both)
        .run()

      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.key shouldBe testAttachmentId
    }

////    First attempt to get the deleteMessage method test to work' A
//    "Deleting message from queue " in {
//      implicit val sqsClient: SqsAsyncClient = mock[SqsAsyncClient]
//      val result = DeleteMessageResponse.builder().build()
//      val resp = DeleteMessageResponse
//      when(sqsClient.deleteMessage(any[DeleteMessageRequest]))
//        .receiptHandle(MessageAction.Delete.==())
//        .take(1).shouldBe(true)
//      sqsClient
//        .deleteMessage(DeleteMessageRequest)
//        .toScala

//      val result = future.futureValue
//      result shouldBe a[SqsDeleteResult]
//
//    }

//    //  The test below was second idea' B
//    "Deleting message a delete request " in {
//          implicit val sqsClient: SqsAsyncClient = mock[SqsAsyncClient]
//          val request = queueService.deleteMessage()
//          when(sqsClient.deleteMessage(any()[DeleteMessageRequest]))
//            .thenCallRealMethod()
//            .via(queueService.getMessages)
//            .toMat(Sink
//              .head)(Keep.!=()).run() { result =>
//            result.!=() shouldBe sqsSampleResponse
//              .map(_ => new SqsDeleteResult(MessageAction, resp))(request)
//          }
      // The third idea which I prefer as its a bit more readable.
//      "Delete message from queue" in {
//        implicit val sqsClient: SqsAsyncClient = mock[SqsAsyncClient]
//        val future = SqsSource(queueUrl, queueService.settings)
////        val future = queueService.deleteMessage()
//              .take(1)
//              .map{
//                case (m, _!=()) => MessageAction.delete(m)
//              }
//              .via(SqsAckSink(queueUrl)(Keep.eq())
//          .runWith(Sink.head))
//
//        val result = future.futureValue
//        result shouldBe a[SqsDeleteResult]
//
//      }
  }

  "For failure scenarios Queue service" should {
    import TestServices.failure._

    "Report parsing message failure" in {
      val sink = TestSink.probe[EitherErr[AttachmentInfo]]

      val (_, sub) = queueService
        .getMessages
        .via(queueService.parseMessages)
        .toMat(sink)(Keep.both)
        .run()

      val result = sub
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.message shouldBe "Parsing SQS message failure"
      result.left.toOption.get.severity shouldBe ERROR
    }

  }
}