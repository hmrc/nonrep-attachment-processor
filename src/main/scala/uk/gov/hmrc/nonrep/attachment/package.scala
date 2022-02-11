package uk.gov.hmrc.nonrep

import akka.http.scaladsl.model.{StatusCode, StatusCodes}
import akka.util.ByteString

package object attachment {
  case class BuildVersion(version: String) extends AnyVal

  case class ClientData(businessId: String, notableEvent: String, retentionPeriod: Int)

  type EitherErr[T] = Either[ErrorMessage, T]

  case class ErrorMessage(message: String, code: StatusCode = StatusCodes.BadRequest, error: Option[Throwable] = None)

  object ErrorMessage {
    def apply(message: String, code: Int): ErrorMessage = ErrorMessage(message, StatusCode.int2StatusCode(code))
  }

  case class AttachmentInfo(key: String, content: Option[ByteString] = None)
}