package uk.gov.hmrc.nonrep.attachment.utils

import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Utility class for JSON to Map conversions
 */
trait JSONUtils {
  private val mapper = new ObjectMapper()
  mapper.configure(FAIL_ON_UNKNOWN_PROPERTIES, false)

  def toJson(value: Any): String = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)

  def jsonStringToMap: String => Map[String, Any] = json =>
    mapper.readValue[Map[String, Any]](json.getBytes("UTF8"), new TypeReference[Map[String, Any]]() {})

  def mapToJsonString: Map[String, Any] => String = map => toJson(map)
}
