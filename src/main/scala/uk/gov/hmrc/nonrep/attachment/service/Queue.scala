package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.stream.scaladsl.{Flow, Source}
import software.amazon.awssdk.services.sqs.model.Message

/**
 * It's an interim object before the final interface is delivered
 */
object Queue {

  // make sure that SQS async client is created with important parameters taken from service config

  def getMessages: Source[Message, NotUsed] = ???

  def parseMessages: Flow[Message, AttachmentInfo, NotUsed] = ???

  private def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] = ???

}
