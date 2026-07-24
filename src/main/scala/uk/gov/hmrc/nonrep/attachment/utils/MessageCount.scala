package uk.gov.hmrc.nonrep.attachment.utils

import java.util.concurrent.atomic.AtomicLong

object MessageCount {
  var msgCount: AtomicLong = new AtomicLong(0)
}
