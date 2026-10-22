package uk.gov.hmrc.nonrep.attachment.utils

import org.apache.pekko.Done
import org.apache.pekko.stream.scaladsl.Sink
import uk.gov.hmrc.nonrep.attachment.{AttachmentError, AttachmentInfo, EitherErr}
import uk.gov.hmrc.nonrep.attachment.server.{NonrepMicroservice, ServiceConfig}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

class TestNonrepMicroservice()(using val system1: org.apache.pekko.actor.typed.ActorSystem[?], val config1: ServiceConfig) extends NonrepMicroservice()(using system1, config1) {
  private val msgSuccess = AtomicInteger(0)
  private val msgFailed = AtomicInteger(0)

  def msgSuccessCount = msgSuccess.get
  def msgFailedCount = msgFailed.get
  def msgCountTotal: Int = msgSuccessCount + msgFailedCount

  val failedMsgList = ListBuffer.empty[AttachmentError]
  def  failedMsgMessages = this.synchronized { failedMsgList.map(_.message).toList }

  override val applicationSink: Sink[EitherErr[AttachmentInfo], Future[Done]] =
    Sink.foreach[EitherErr[AttachmentInfo]] {
      _.fold( err =>  {
        msgFailed.incrementAndGet()
        system.log.info(s"Test SinkMessage Error called: ${err.message}")
        failedMsgList +=  err
        errorHandler(err)
      },
        attachmentInfo =>
          msgSuccess.incrementAndGet()
          system.log.info(s"Test SinkMessage Successful processing of attachment")
      )
    }
}

