package uk.gov.hmrc.nonrep.attachment.app.json

import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.hmrc.nonrep.attachment.BuildVersion

object JsonFormats extends DefaultJsonProtocol {

  implicit val buildVersionJsonFormat: RootJsonFormat[BuildVersion] = jsonFormat1(BuildVersion)
}