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


  sealed trait AttachmentError {
    def message: String

    def severity: Severity
  }

  type EitherErr[T] = Either[AttachmentError, T]

  case class ErrorMessageWithDeleteSQSMessage(messageId: String, message: String, optThrowable: Option[Throwable]= None, severity: Severity = ERROR) extends AttachmentError

  case class ErrorMessage(message: String, optThrowable: Option[Throwable] = None, severity: Severity = ERROR) extends AttachmentError

  case class AttachmentInfo(message: String, key: String, notableEvent: String = "vat-registration", submissionId: Option[String] = None)

  case class AttachmentContent(info: AttachmentInfo, content: ByteString) {
    val bytes: Array[Byte] = content.toArray
  }

  case class ArchivedAttachment(info: AttachmentInfo, archiveId: String, vaultName: String)

  case class ZipContent(info: AttachmentInfo, attachment: Array[Byte], metadata: Array[Byte])
  case class SignedZipContent(info: AttachmentInfo, signedAttachment: Array[Byte], attachment: Array[Byte], metadata: Array[Byte]) {
    lazy val files = Seq(
      SIGNED_ATTACHMENT_FILE -> signedAttachment,
      ATTACHMENT_FILE -> attachment,
      METADATA_FILE -> metadata
    )
  }

  object SignedZipContent {
    def apply(content: ZipContent, signedAttachment: Array[Byte]): SignedZipContent = {
      SignedZipContent(
        content.info,
        signedAttachment,
        content.attachment,
        content.metadata
      )
    }
  }
}
