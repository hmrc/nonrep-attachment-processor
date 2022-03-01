package uk.gov.hmrc.nonrep.attachment
package service

import akka.util.ByteString
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{verify, when}
import org.mockito.MockitoSugar.mock
import org.mockito.internal.stubbing.answers.Returns
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.glacier.GlacierAsyncClient
import software.amazon.awssdk.services.glacier.model._
import uk.gov.hmrc.nonrep.attachment.service.ChecksumUtils.{chunkSize, sha256TreeHashHex}

import java.util.Collections.emptyList
import java.util.concurrent.CompletableFuture.completedFuture
import scala.concurrent.{ExecutionContext, Future}

class GlacierSpec extends BaseSpec {
  import TestServices._

  private val glacierAsyncClient = mock[GlacierAsyncClient]

  private val glacier = new Glacier() {
    override val client: GlacierAsyncClient = glacierAsyncClient
  }

  private implicit val ec: ExecutionContext = scala.concurrent.ExecutionContext.Implicits.global

  private def future(result: Object) = new Returns(completedFuture(result))

  "eventuallyUploadAttachment" should {
    val archiveId = "archiveId"
    val content = AttachmentContent(AttachmentInfo("messageId", testAttachmentId), ByteString(sampleAttachment))

    val uploadArchiveRequest =
      UploadArchiveRequest
        .builder()
        .vaultName("vaultName")
        .checksum(sha256TreeHashHex(content.bytes))
        .contentLength(content.bytes.length.toLong)
        .build()

    "upload an attachment to Glacier" when {
      "the glacier client call succeeds" in {
        when(glacier.client.uploadArchive(ArgumentMatchers.eq(uploadArchiveRequest), any[AsyncRequestBody]()))
          .thenAnswer(future(UploadArchiveResponse.builder().archiveId(archiveId).build))

        glacier.eventuallyUploadAttachment(content).futureValue.toOption.get shouldBe archiveId
      }
    }

    "return an error message" when {
      "the glacier client call fails" in {
        when(
          glacierAsyncClient
            .uploadArchive(ArgumentMatchers.eq(uploadArchiveRequest), any[AsyncRequestBody]))
            .thenAnswer(future(new RuntimeException("boom!")))

        glacier.eventuallyUploadAttachment(content).futureValue match {
          case Left(error) => error.message shouldBe s"Error uploading attachment $content to glacier vaultName"
          case Right(_) => fail("expected error message")
        }
      }
    }

    "create a glacier vault" when {
      "the vault does not exist" in {
        val glacierWithoutVault = new Glacier() {
          override val client: GlacierAsyncClient = glacierAsyncClient

          private var vaultExists = false

          override def eventuallyUpload(content: AttachmentContent)(implicit executor: ExecutionContext): Future[UploadArchiveResponse] =
            if (vaultExists) {
              Future successful UploadArchiveResponse.builder().archiveId(archiveId).build()
            } else {
              vaultExists = true
              Future failed ResourceNotFoundException.builder.build()
            }
        }

        when(
          glacierAsyncClient
            .listVaults(any[ListVaultsRequest]))
            .thenAnswer(future(ListVaultsResponse.builder().vaultList(emptyList[DescribeVaultOutput]()).build()))
        when(glacierAsyncClient.createVault(any[CreateVaultRequest])).thenAnswer(future(CreateVaultRequest.builder().build()))

        glacierWithoutVault.eventuallyUploadAttachment(content).futureValue.toOption.get shouldBe archiveId

        verify(glacierAsyncClient.setVaultNotifications(any[SetVaultNotificationsRequest]))
      }
    }
  }

  "sha256TreeHashHex" should {
    "calculate a checksum" when {
      "the payload size is less than or equal to the chunk size" in {
        sha256TreeHashHex(sampleAttachment) shouldBe "029048240bcbd99128624f8e0ed1a456dff34eebbe911663e7800fd3f6c0c9e4"
      }

      "the payload size is greater than the chunk size" in {
        val payloadLargerThanChunkSize = Range(0, chunkSize).mkString.getBytes
        payloadLargerThanChunkSize.size > chunkSize shouldBe true
        sha256TreeHashHex(payloadLargerThanChunkSize) shouldBe "ead1616b46a4a09a998d0c0c014bffe385a6c09977bd504bbe805310775f131f"
      }
    }

    "tolerate an empty payload" in {
      sha256TreeHashHex(Array.empty[Byte]) shouldBe ""
    }
  }
}
