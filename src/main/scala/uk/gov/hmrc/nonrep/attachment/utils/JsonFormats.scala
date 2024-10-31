package uk.gov.hmrc.nonrep.attachment
package utils

import spray.json.{DefaultJsonProtocol, RootJsonFormat}

object JsonFormats extends DefaultJsonProtocol {

  implicit val buildVersionJsonFormat: RootJsonFormat[BuildVersion] = jsonFormat1(BuildVersion)
}