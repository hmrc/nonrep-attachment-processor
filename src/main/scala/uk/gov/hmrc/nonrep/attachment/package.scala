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

  type EitherErr[T] = Either[ErrorMessage, T]

  case class ErrorMessage(message: String, severity: Severity = ERROR)

  case class AttachmentInfo(message: String, key: String)

  case class AttachmentContent(info: AttachmentInfo, content: ByteString)

  case class ZipContent(info: AttachmentInfo, files: Seq[(String, Attachment)])
}