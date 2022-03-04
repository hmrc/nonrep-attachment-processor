package uk.gov.hmrc.nonrep.attachment.service


import software.amazon.awssdk.services.sqs.model.{DeleteMessageResponse, Message}

import java.util.UUID.randomUUID


class QueueSpec extends Basespec {

  "Queue service should" {
  "Read an sqs service message" in {
    private def readQueue = {
      val uuid = randomUUID().toString
      Message.builder().receiptHandle(uuid).body(sqsMessage(uuid)).build()
    }
  }

  }
}
