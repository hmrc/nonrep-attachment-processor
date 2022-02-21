package uk.gov.hmrc.nonrep

import akka.util.ByteString
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.ObjectMapper

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

 val queueUrl = "https://sqs.eu-west-2.amazonaws.com/20304005294/MyQueue"  //example to be changed
}