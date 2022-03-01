package uk.gov.hmrc.nonrep.attachment.service

import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{doThrow, when}
import org.mockito.scalatest.MockitoSugar
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{DeleteMessageRequest, DeleteMessageResponse, ReceiveMessageRequest, ReceiveMessageResponse}
import uk.gov.hmrc.nonrep.attachment.{BaseSpec, DataSamples}
import uk.gov.hmrc.nonrep.attachment.utils.JSONUtils

import java.util.UUID.randomUUID
import scala.jdk.CollectionConverters._

class QueueSpec extends BaseSpec with MockitoSugar with DataSamples {
  private val service = new Queue with JSONUtils {
    override val sqsReadClient: SqsClient = mock[SqsClient]
    override val sqsDeleteClient: SqsClient = mock[SqsClient]
  }

  private val sqs = "test"

  "SqsService service" should {
    "return at most required portion of data when reading SQS" in {
      val limit = 10
      val result = ReceiveMessageResponse.builder().messages(sqsMessages.asJava).build()
      when(service.sqsReadClient.receiveMessage(any[ReceiveMessageRequest]())).thenReturn(result)

      service.readQueueMessages(, limit).toOption.get.size <= limit shouldBe true
    }

    "return string representation of aws response after deleting message from SQS" in {
      val result = DeleteMessageResponse.builder().build()
      when(service.sqsDeleteClient.deleteMessage(any[DeleteMessageRequest]())).thenReturn(result)

      val deleteMessageResult = service.deleteMessage(sqs, randomUUID().toString).toOption.get
      deleteMessageResult shouldBe result.toString
    }

    "return correct number of SQS messages after parsing" in {
      service.parseSQSMessage("test-handle", sqsMessage(randomUUID().toString)).get.size shouldBe 1
    }

    "parse SQS message properly" in {
      val key = randomUUID().toString
      val handle = "test-handle"

      val bundle = service.parseSQSMessage(handle, sqsMessage(key)).get.head
      bundle.handle shouldBe handle
      bundle.key shouldBe s"$key.zip"
      bundle.bucket shouldBe "test-nonrep-attachment-processor-data"
    }

    "ignore S3 test messages" in {
      service.parseSQSMessage("test-handle", sqsMessageS3TestEvent) shouldBe None
    }

    "support runtime exception when deleting message" in {
      doThrow(new RuntimeException("error")).when(service.sqsDeleteClient).deleteMessage(any[DeleteMessageRequest]())
      service.deleteMessage(sqs, "").isLeft shouldBe true
    }

    "support runtime exception when reading messages" in {
      doThrow(new RuntimeException("error")).when(service.sqsReadClient).receiveMessage(any[ReceiveMessageRequest]())
      service.readQueue(sqs, 10).isLeft shouldBe true
    }
  }
}

