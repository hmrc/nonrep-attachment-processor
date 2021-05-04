package uk.gov.hmrc.nonrep.attachment
package utils

import spray.json.DefaultJsonProtocol

object JsonFormats extends DefaultJsonProtocol {

  implicit val buildVersionJsonFormat = jsonFormat1(BuildVersion)
}