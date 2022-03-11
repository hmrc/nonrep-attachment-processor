package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.ActorAttributes
import akka.stream.Supervision.stoppingDecider
import akka.stream.scaladsl.Flow
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.glacier.GlacierAsyncClient
import software.amazon.awssdk.services.glacier.model._
import uk.gov.hmrc.nonrep.attachment.service.ChecksumUtils.sha256TreeHashHex
import java.lang.Integer.toHexString
import java.security.MessageDigest.getInstance
import java.time.LocalDate.now

import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.annotation.tailrec
import scala.compat.java8.FutureConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

trait Glacier {
  val archive: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed]
}

class GlacierService()
                    (implicit val config: ServiceConfig,
                     implicit val system: ActorSystem[_]) extends Glacier {

  private val glacierNotificationsSnsTopicArn = config.glacierNotificationsSnsTopicArn
  private val environment = config.env

  private [service] lazy val client: GlacierAsyncClient = GlacierAsyncClient.builder().build()

  private val environmentalVaultNamePrefix =
    if (Set("dev", "qa", "staging", "production").contains(environment)) ""
    else s"$environment-"

  implicit val ec: ExecutionContext = system.executionContext

  override val archive: Flow[EitherErr[AttachmentContent], EitherErr[ArchivedAttachment], NotUsed] =
    Flow[EitherErr[AttachmentContent]].mapAsyncUnordered(8) {
      case Right(attachmentContent) =>
        eventuallyCreateVaultIfNecessaryAndArchive(attachmentContent, datedVaultName).map { archiveIdOrError: EitherErr[String] =>
          archiveIdOrError.map(archiveId => ArchivedAttachment(attachmentContent.info, archiveId, datedVaultName))
        }
      case Left(e) =>
        Future successful Left(e)
    }.withAttributes(ActorAttributes.supervisionStrategy(stoppingDecider))
  //TODO: determine whether restartingDecider or stoppingDecider is more appropriate for keeping stream alive
  //presumably this would depend on the type of error we've encountered
  //e.g. message level vs resource (glacier/network) errors, and is it transient or persistent?
  //there is also backoffWithRetry but that too can be very wasteful given a general error e.g. network outage.
  //see discussion here for example
  //https://blog.colinbreck.com/backoff-and-retry-error-handling-for-akka-streams/

  private[service] def eventuallyCreateVaultIfNecessaryAndArchive(content: AttachmentContent, vaultName: String): Future[EitherErr[String]] =
    eventuallyArchive(
        UploadArchiveRequest
          .builder()
          .vaultName(vaultName)
          .checksum(sha256TreeHashHex(content.bytes))
          .contentLength(content.bytes.length.toLong)
          .build(),
        AsyncRequestBody.fromBytes(content.bytes))
      .map(uploadResponse => Right(uploadResponse.archiveId()))
      .recoverWith[EitherErr[String]] {
        case _: ResourceNotFoundException =>
          for {
            _ <- eventuallyCreateVaultIfItDoesNotExist(vaultName)
            uploadResponse <- eventuallyCreateVaultIfNecessaryAndArchive(content, vaultName)
          } yield uploadResponse
        case _ =>
          Future successful Left(ErrorMessage(s"Error uploading attachment $content to glacier $vaultName"))
    }

  private[service] def eventuallyArchive(uploadArchiveRequest: UploadArchiveRequest,
                                         asyncRequestBody: AsyncRequestBody): Future[UploadArchiveResponse] =
    client.uploadArchive(uploadArchiveRequest, asyncRequestBody).toScala

  //TODO: this functionality must be kept in sync with sign service.
  // This includes the configured value of glacierNotificationsSnsTopicArn and the event name "ArchiveRetrievalCompleted".
  // As an alternative consider adding a microservice to handle interactions with Glacier and deferring to that.
  private[service] def eventuallyCreateVaultIfItDoesNotExist(vaultName: String) = {
    def vaultEventuallyExists() =
      client.listVaults(ListVaultsRequest.builder().build()).toScala.map { listVaultsResponse =>
        listVaultsResponse.vaultList().asScala.toSeq.exists(_.vaultName == vaultName)
      }

    def eventuallyCreateVault() =
      client.createVault(CreateVaultRequest.builder().vaultName(vaultName).build()).toScala

    def eventuallySetVaultNotifications() =
      client.setVaultNotifications(
        SetVaultNotificationsRequest
          .builder()
          .vaultName(vaultName)
          .vaultNotificationConfig(
            VaultNotificationConfig.builder().snsTopic(glacierNotificationsSnsTopicArn).events("ArchiveRetrievalCompleted").build)
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

  private[service] def datedVaultName = s"${environmentalVaultNamePrefix}vat-return-${now().getYear}"
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
