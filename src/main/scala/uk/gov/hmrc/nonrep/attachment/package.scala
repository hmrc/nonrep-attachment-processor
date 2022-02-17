package uk.gov.hmrc.nonrep

import akka.util.ByteString

package object attachment {

  sealed trait Severity
  case object ERROR extends Severity
  case object WARN extends Severity

  case class BuildVersion(version: String) extends AnyVal

  case class ClientData(businessId: String, notableEvent: String, retentionPeriod: Int)

  type EitherErr[T] = Either[ErrorMessage, T]

  case class ErrorMessage(message: String, severity: Severity = ERROR)

  case class AttachmentInfo(message: String, key: String)

  case class AttachmentContent(info: AttachmentInfo, content: ByteString)

  case class ZipContent(info: AttachmentInfo, files: Seq[(String, Array[Byte])])

  type SQSMessageParser = (String, String) => Option[List[AttachmentInfo]]

}