package uk.gov.hmrc.nonrep

import akka.util.ByteString

package object attachment {

  type Attachment = Array[Byte]

  sealed trait Severity
  case object ERROR extends Severity
  case object WARN extends Severity

  val METADATA_FILE = "metadata.json"
  val ATTACHMENT_FILE = "attachment.data"
  val SIGNED_ATTACHMENT_FILE = "signed-attachment.p7m"

  case class BuildVersion(version: String) extends AnyVal

  case class ClientData(businessId: String, notableEvent: String, retentionPeriod: Int)


  sealed trait Error {
    def message: String

    def severity: Severity
  }

  type EitherErr[T] = Either[Error, T]

  case class FailedToDownloadS3BundleError(messageId: String, s3Object: String, bucket: String) extends Error {
    override def message = s"failed to download $s3Object attachment bundle from s3 $bucket"

    override def severity: Severity = WARN
  }
  case class ErrorMessage(message: String, severity: Severity = ERROR) extends Error

  case class AttachmentInfo(message: String, key: String, notableEvent: String = "vat-registration", submissionId: Option[String] = None)

  case class AttachmentContent(info: AttachmentInfo, content: ByteString) {
    val bytes: Array[Byte] = content.toArray
  }

  case class ArchivedAttachment(info: AttachmentInfo, archiveId: String, vaultName: String)

  case class ZipContent(info: AttachmentInfo, files: Seq[(String, Attachment)])
}
