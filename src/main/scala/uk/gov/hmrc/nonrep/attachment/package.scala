package uk.gov.hmrc.nonrep

package object attachment {
  case class BuildVersion(version: String) extends AnyVal

  case class ClientData(businessId: String, notableEvent: String, retentionPeriod: Int)

  case class AttachmentInfo(key: String)
}