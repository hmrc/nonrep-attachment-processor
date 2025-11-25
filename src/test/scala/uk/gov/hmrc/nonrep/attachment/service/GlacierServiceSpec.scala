package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.http.javadsl.model.DateTime.now
import org.apache.pekko.util.ByteString
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.when
import org.scalatestplus.mockito.MockitoSugar.mock
import org.mockito.internal.stubbing.answers.Returns
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.glacier.GlacierAsyncClient
import software.amazon.awssdk.services.glacier.model.*
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.service.ChecksumUtils.{chunkSize, sha256TreeHashHex}

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture

class GlacierServiceSpec extends BaseSpec {

  import TestServices.*

  private val glacierAsyncClient = mock[GlacierAsyncClient]

  private def glacierService(sc: ServiceConfig = config): GlacierService =
    new GlacierService()(using sc, typedSystem ) {
       override lazy val client: GlacierAsyncClient = glacierAsyncClient
    }

  private def serviceConfig(environment: String) =
    new ServiceConfig() {
      override val env: String       = environment
      override def sqsSystemProperty = "local"
    }

  private def future(result: Object) = new Returns(completedFuture(result))

  "eventuallyCreateVaultIfNecessaryAndUpload" should {
    val archiveId = "archiveId"
    val vaultName = s"local-vat-registration-${now().year()}"
    val content   = AttachmentContent(AttachmentInfo(testAttachmentId, "messageId", testS3ObjectKey), ByteString(sampleAttachment))

    val uploadArchiveRequest =
      UploadArchiveRequest
        .builder()
        .vaultName(vaultName)
        .checksum(sha256TreeHashHex(content.bytes))
        .contentLength(content.bytes.length.toLong)
        .build()

    "archive an attachment in Glacier" when {
      "the glacier client call succeeds" in {
        when(glacierService().client.uploadArchive(ArgumentMatchers.eq(uploadArchiveRequest), any[AsyncRequestBody]()))
          .thenAnswer(future(UploadArchiveResponse.builder().archiveId(archiveId).build))

        glacierService().eventuallyArchive(content, vaultName).futureValue.toOption.get shouldBe archiveId
      }
    }

    "return an error" when {
      def eventualError(exception: Exception) = {
        val error: CompletableFuture[UploadArchiveResponse] = new CompletableFuture[UploadArchiveResponse]()
        error.completeExceptionally(exception)
        new Returns(error)
      }

      "the glacier client call fails" in {
        when(glacierAsyncClient.uploadArchive(ArgumentMatchers.eq(uploadArchiveRequest), any[AsyncRequestBody]))
          .thenAnswer(eventualError(new RuntimeException("boom!")))

        glacierService().eventuallyArchive(content, vaultName).futureValue match {
          case Left(error) =>
            error.message  shouldBe s"Error uploading attachment $content to glacier $vaultName"
            error.severity shouldBe ERROR
          case Right(_)    =>
            fail("an error was expected")
        }
      }

      "the vault is not found" in {
        when(glacierAsyncClient.uploadArchive(ArgumentMatchers.eq(uploadArchiveRequest), any[AsyncRequestBody]))
          .thenAnswer(eventualError(ResourceNotFoundException.builder().message("boom").build()))

        glacierService().eventuallyArchive(content, vaultName).futureValue match {
          case Left(error) =>
            error.message  shouldBe
              s"Vault $vaultName not found for attachment $content. The sign service should create the vault in due course."
            error.severity shouldBe WARN
          case Right(_)    =>
            fail("an error was expected")
        }
      }
    }
  }

  "datedVaultName" should {
    "return a vault name without a prefix" when {
      val vaultNameWithNoPrefix = s"vat-registration-${now().year()}"

      "running in dev" in {
        glacierService(serviceConfig("dev")).datedVaultName shouldBe vaultNameWithNoPrefix
      }

      "running in qa" in {
        glacierService(serviceConfig("qa")).datedVaultName shouldBe vaultNameWithNoPrefix
      }

      "running in staging" in {
        glacierService(serviceConfig("staging")).datedVaultName shouldBe vaultNameWithNoPrefix
      }

      "running in production" in {
        glacierService(serviceConfig("production")).datedVaultName shouldBe vaultNameWithNoPrefix
      }
    }

    "return a vault name with the environment name as prefix" when {
      "running in another environment" in {
        glacierService(serviceConfig("sandbox1")).datedVaultName shouldBe s"sandbox1-vat-registration-${now().year()}"
      }
    }
  }

  "sha256TreeHashHex" should {
    "calculate a checksum" when {
      "the payload size is less than or equal to the chunk size" in {
        sha256TreeHashHex(sampleAttachment) shouldBe "eed701e348197b3fd82a0cf9e11659a689ea0840e6f05a5bd31e4a145c2a3e7c"
      }

      "the payload size is greater than the chunk size" in {
        val payloadLargerThanChunkSize = Range(0, chunkSize).mkString.getBytes
        payloadLargerThanChunkSize.size > chunkSize   shouldBe true
        sha256TreeHashHex(payloadLargerThanChunkSize) shouldBe "ead1616b46a4a09a998d0c0c014bffe385a6c09977bd504bbe805310775f131f"
      }
    }

    "tolerate an empty payload" in {
      sha256TreeHashHex(Array.empty[Byte]) shouldBe ""
    }
  }
}
