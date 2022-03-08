package uk.gov.hmrc.nonrep.attachment
package service

import akka.Done
import akka.stream.alpakka.sqs.{MessageAction, SqsSourceSettings}
import akka.stream.alpakka.sqs.scaladsl.{SqsAckFlow, SqsAckSink, SqsSource}
import akka.stream.scaladsl.{Keep, Sink}
import org.mockito.ArgumentMatchers.any
import org.mockito.MockitoSugar.{spy, verify}
import software.amazon.awssdk.regions.Region.EU_WEST_2
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, SendMessageRequest}
import uk.gov.hmrc.nonrep.attachment.BaseSpec
import uk.gov.hmrc.nonrep.attachment.TestServices.config.env
import uk.gov.hmrc.nonrep.attachment.TestServices.success.queueService
import uk.gov.hmrc.nonrep.attachment.TestServices.success.queueService.getMessages

import scala.concurrent.duration.DurationInt

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

    //  "get message from queue using queue url" in {
    //
    //    val future: Any = queueService.getMessages
    //      .take(1)
    //      .via(SqsAckFlow(config.queueUrl))
    //      .runWith(Sink.head)
    //      .build()
    //    }
    //      }

    "parse message properly" in {
      whenReady(queueService.getMessages.via(queueService.parseMessages).toMat(Sink.head)(Keep.right).run()) { info =>
        info.key shouldBe testAttachmentId
      }
    }

//    "deleting message from queue threw runtime exception" in {
//
//      val future = queueService.getMessages
//        .take(1)
//        .map(MessageAction.Delete(_))
//        .runWith(SqsAckSink(config.queueUrl))
//
//      future.futureValue shouldBe Done
//    }
  }
}
