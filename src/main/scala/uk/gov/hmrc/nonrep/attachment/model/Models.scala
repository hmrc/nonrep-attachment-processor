package uk.gov.hmrc.nonrep.attachment.model

sealed trait Model

case class SQSMessage(handle: String, body: String) extends Model

case class ProcessingBundle(
                             handle: String,
                             bucket: String,
                             key: String,
                             payload: Option[Array[Byte]] = None,
                             metadata: Option[Array[Byte]] = None,
                             signedPayload: Option[Array[Byte]] = None,
                             signedMetadata: Option[Array[Byte]] = None) extends Model

case class BundleEntry(name: String, content: Array[Byte]) extends Model

case class CadesExtraData(
                           payloadMessageDigest: String,
                           payloadMessageDigestType: String,
                           signatureTimestamp: String) extends Model

case class EnrichedMetadata(metadataJsonString: String, signingTime: String) extends Model

final case class BuildVersion(version: String) extends AnyVal

case class ErrorMessage(message: String, error: Option[Throwable] = None)