package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import software.amazon.awssdk.services.sqs.model.Message

/**
 * It's an interim object before the final interface is delivered
 */

trait Queue {
  def getMessages: Source[Message, NotUsed]

  def parseMessages: Flow[Message, AttachmentInfo, NotUsed]

  def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed]
}

class QueueService extends Queue {

  // make sure that SQS async client is created with important parameters taken from service config

  /*
   * all these implementations are likely to be replaced
   */
  override def getMessages: Source[Message, NotUsed] = Source.empty

  override def parseMessages: Flow[Message, AttachmentInfo, NotUsed] = Flow.fromFunction((m: Message) => AttachmentInfo(m.messageId(), ""))

  override def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] = Flow.fromFunction((_: AttachmentInfo) => true)

}
