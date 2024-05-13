package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.ActorAttributes
import akka.stream.Supervision.restartingDecider
import akka.stream.scaladsl.Flow
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region.EU_WEST_2
import software.amazon.awssdk.services.glacier.GlacierAsyncClient
import software.amazon.awssdk.services.glacier.model._
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.service.ChecksumUtils.sha256TreeHashHex

import java.lang.Integer.toHexString
import java.security.MessageDigest.getInstance
import java.time.LocalDate.now
import scala.annotation.tailrec
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}

trait Glacier {
  val archive: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed]
}

class GlacierService()(implicit val config: ServiceConfig, implicit val system: ActorSystem[_]) extends Glacier {
  private[service] lazy val client: GlacierAsyncClient = GlacierAsyncClient
    .builder()
    .region(EU_WEST_2)
    .httpClientBuilder(NettyNioAsyncHttpClient.builder())
    .build()

  private val environmentalVaultNamePrefix = if (config.isSandbox) s"${config.env}-" else ""

  implicit val ec: ExecutionContext = system.executionContext

  override val archive: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] =
    Flow[EitherErr[AttachmentContent]].mapAsyncUnordered(8) {
      case Right(attachmentContent) =>
        eventuallyArchive(attachmentContent, datedVaultName).map { archiveIdOrError: EitherErr[String] =>
          archiveIdOrError.map(archiveId => ArchivedAttachment(attachmentContent.info, archiveId, datedVaultName))
        }
      case Left(e) =>
        Future successful Left(e)
    }.withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))

  private[service] def eventuallyArchive(content: AttachmentContent, vaultName: String): Future[EitherErr[String]] =
    eventuallyUploadArchive(
      UploadArchiveRequest
        .builder()
        .vaultName(vaultName)
        .checksum(sha256TreeHashHex(content.bytes))
        .contentLength(content.bytes.length.toLong)
        .build(),
      AsyncRequestBody.fromBytes(content.bytes))
      .map(uploadResponse => Right(uploadResponse.archiveId()))
      .recoverWith[EitherErr[String]] {
        case exception: ResourceNotFoundException =>
          Future.successful(Left(
            ErrorMessage(
              s"Vault $vaultName not found for attachment $content. The sign service should create the vault in due course.",
              Some(exception),
              WARN)))
        case exception =>
          Future.successful(Left(ErrorMessage(s"Error uploading attachment $content to glacier $vaultName", Some(exception))))
      }

  private[service] def eventuallyUploadArchive(uploadArchiveRequest: UploadArchiveRequest,
                                               asyncRequestBody: AsyncRequestBody): Future[UploadArchiveResponse] =
    client.uploadArchive(uploadArchiveRequest, asyncRequestBody).toScala

  private[service] def datedVaultName = s"${environmentalVaultNamePrefix}vat-registration-${now().getYear}"
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
   * Consumes a sequence of SHA 256 hashes to produce a single concatenated hash.
   * One way to do this would be to create a merkle tree structure.
   * A merkle tree is a binary tree where each branch node contains the concatenation of the hash of its children.
   * We don't need the whole merkle tree structure, simply the hash value held in the root node.
   * For the first call in the recursive chain, each hash in the sequence is equivalent to a leaf node of a merkle tree.
   * Each recursive step moves us up the implied tree towards the root node.
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
