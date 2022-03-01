package uk.gov.hmrc.nonrep.attachment
package service

import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.services.glacier.GlacierAsyncClient
import software.amazon.awssdk.services.glacier.model._
import uk.gov.hmrc.nonrep.attachment.service.ChecksumUtils.sha256TreeHashHex

import java.lang.Integer.toHexString
import java.security.MessageDigest.getInstance
import scala.annotation.tailrec
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

class Glacier() {
  private [service] val client: GlacierAsyncClient =
    GlacierAsyncClient.builder().httpClient(NettyNioAsyncHttpClient.create()).build()

  // to do - what are the correct values?
  private val vaultName = "vaultName"
  private val snsTopic = "snsTopic"
  private val event = "event"

  protected def eventuallyUpload(content: AttachmentContent)(implicit executor: ExecutionContext): Future[UploadArchiveResponse] =
    client
      .uploadArchive(
        UploadArchiveRequest
          .builder()
          .vaultName(vaultName)
          .checksum(sha256TreeHashHex(content.bytes))
          .contentLength(content.bytes.length.toLong)
          .build(),
        AsyncRequestBody.fromBytes(content.bytes))
      .toScala

  def eventuallyUploadAttachment(content: AttachmentContent)(implicit executor: ExecutionContext): Future[EitherErr[String]] =
    eventuallyUpload(content).map{ uploadResponse =>
      Right(uploadResponse.archiveId())
    }.recoverWith[EitherErr[String]] {
      case _: ResourceNotFoundException =>
        for {
          _ <- eventuallyCreateVaultIfItDoesNotExist()
          uploadResponse <- eventuallyUploadAttachment(content)
        } yield uploadResponse
      case _ =>
        Future successful Left(ErrorMessage(s"Error uploading attachment $content to glacier $vaultName"))
    }

  private[service] def eventuallyCreateVaultIfItDoesNotExist()(implicit executor: ExecutionContext) = {
    def vaultEventuallyExists()(implicit executor: ExecutionContext) =
      client.listVaults(ListVaultsRequest.builder().build()).toScala.map { listVaultsResponse =>
        listVaultsResponse.vaultList().asScala.toSeq.exists(_.vaultName == vaultName)
      }

    def eventuallyCreateVault()(implicit executor: ExecutionContext) =
      client.createVault(CreateVaultRequest.builder().vaultName(vaultName).build()).toScala

    // TODO: what topic and event should we use?
    def eventuallySetVaultNotifications()(implicit executor: ExecutionContext) =
      client.setVaultNotifications(
        SetVaultNotificationsRequest
          .builder()
          .vaultName(vaultName)
          .vaultNotificationConfig(VaultNotificationConfig.builder().snsTopic(snsTopic).events(event).build)
          .build
      ).toScala

    (
      for {
        vaultExists <- vaultEventuallyExists()
        if !vaultExists
        _ <- eventuallyCreateVault()
        _ <- eventuallySetVaultNotifications()
      } yield Right(vaultExists)
    ).recover {
      case e => Left(ErrorMessage(s"Error creating glacier vault $vaultName: ${e.getMessage}"))
    }
  }
}

object ChecksumUtils {
  val chunkSize: Int = 1024 * 1024

  def sha256TreeHashHex(payload: Array[Byte]): String =
    if (payload.isEmpty) "" else toHex(sha256TreeHash(splitPayloadIntoChunksAndSha256HashEachChunk(payload)))

  private def sha256MessageDigest = getInstance("SHA-256")

  private def splitPayloadIntoChunksAndSha256HashEachChunk(payload: Array[Byte]) =
    payload.grouped(chunkSize).map(chunk => sha256MessageDigest.digest(chunk)).toSeq

  private def concatenate(sha256Hash1: Array[Byte], sha256Hash2: Array[Byte]) = {
    val messageDigest = sha256MessageDigest
    messageDigest.update(sha256Hash1)
    messageDigest.update(sha256Hash2)
    messageDigest.digest
  }

  @tailrec
  private def sha256TreeHash(sha256Hashes: Seq[Array[Byte]]): Array[Byte] =
  /*
   * Consumes a sequence of SHA 256 hashes.
   * For the first call in the recursive chain, each hash can be considered as a leaf node of a merkle tree.
   * We don't need the whole merkle tree structure, simply the hashed value held in the root node.
   */
    if (sha256Hashes.tail.isEmpty) {
      // If there is only a single hash then this is the root hash of the merkle tree so we use it
      sha256Hashes.head
    } else {
      /*
       * If there are multiple hashes then produce parent merkle tree nodes.
       * Split the hashes into pairs and return the concatenation of the hashes of each pair.
       * Also return any single remaining node if the number of hashes was odd.
       * The recursion will continue until we have only a root node containing the concatenation of all the hashes.
       */
      val concatenatedPairsOfSha256Hashes = sha256Hashes.grouped(2).map {
        case Seq(sha256Hash1, sha256Hash2) => concatenate(sha256Hash1, sha256Hash2)
        case Seq(sha256Hash) => sha256Hash
      }.toIndexedSeq

      sha256TreeHash(concatenatedPairsOfSha256Hashes)
    }

  private def toHex(data: Array[Byte]) = {
    val stringBuilder = new StringBuilder(data.length * 2)

    data.indices.foreach { index =>
      val hex = toHexString(data(index) & 0xFF)
      if (hex.length == 1) stringBuilder.append("0")
      stringBuilder.append(hex)
    }

    stringBuilder.toString.toLowerCase
  }
}
