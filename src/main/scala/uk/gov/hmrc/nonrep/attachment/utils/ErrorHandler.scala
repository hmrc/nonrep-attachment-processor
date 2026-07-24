package uk.gov.hmrc.nonrep.attachment.utils

import org.apache.pekko.actor.typed.ActorSystem
import uk.gov.hmrc.nonrep.attachment.{AttachmentError, ERROR, ErrorMessage, ErrorMessageWithDeleteSQSMessage, WARN}

trait ErrorHandler {

  def errorHandler(using system: ActorSystem[?]): AttachmentError => Unit = {
    case ErrorMessageWithDeleteSQSMessage(_, message, None, WARN)             => system.log.warn("Processor: " + message)
    case ErrorMessageWithDeleteSQSMessage(_, message, Some(throwable), WARN)  => system.log.warn("Processor: " + message, throwable)
    case ErrorMessageWithDeleteSQSMessage(_, message, None, ERROR)            => system.log.error("Processor: " + message)
    case ErrorMessageWithDeleteSQSMessage(_, message, Some(throwable), ERROR) => system.log.error("Processor: " + message, throwable)
    case ErrorMessage(message, None, WARN)                                    => system.log.warn("Processor: " + message)
    case ErrorMessage(message, Some(throwable), WARN)                         => system.log.warn("Processor: " + message, throwable)
    case ErrorMessage(message, None, ERROR)                                   => system.log.error("Processor: " + message)
    case ErrorMessage(message, Some(throwable), ERROR)                        => system.log.error("Processor: " + message, throwable)
    case err                                                                  => system.log.error("Processor: " + s"ErrorHandler OTHER  ${err.message}")
  }
}
