package uk.gov.hmrc.nonrep.attachment.model

sealed trait Model

final case class BuildVersion(version: String) extends AnyVal

case class ErrorMessage(message: String, error: Option[Throwable] = None)