package uk.gov.hmrc.nonrep.attachment.utils

import akka.actor.typed.ActorSystem
import uk.gov.hmrc.nonrep.attachment.{AttachmentError, ERROR, ErrorMessage, ErrorMessageWithDeleteSQSMessage, WARN}

trait ErrorHandler {

  def errorHandler(implicit system: ActorSystem[_]): AttachmentError => Unit = {
    case ErrorMessageWithDeleteSQSMessage(_, message, None, WARN) => system.log.warn(message)
    case ErrorMessageWithDeleteSQSMessage(_, message, Some(throwable), WARN) => system.log.warn(message, throwable)
    case ErrorMessageWithDeleteSQSMessage(_, message, None, ERROR) => system.log.error(message)
    case ErrorMessageWithDeleteSQSMessage(_, message, Some(throwable), ERROR) => system.log.error(message, throwable)
    case ErrorMessage(message, None, WARN) => system.log.warn(message)
    case ErrorMessage(message, Some(throwable), WARN) => system.log.warn(message, throwable)
    case ErrorMessage(message, None, ERROR) => system.log.error(message)
    case ErrorMessage(message, Some(throwable), ERROR) => system.log.error(message, throwable)
  }
}
