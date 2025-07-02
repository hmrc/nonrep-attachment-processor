package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.http.client.utils.URIBuilder
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethod, HttpRequest}
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.http.auth.spi.signer.SignRequest
import software.amazon.awssdk.http.{SdkHttpFullRequest, SdkHttpMethod}
import software.amazon.awssdk.regions.Region

import java.io.ByteArrayInputStream
import java.net.URI
import java.time.Instant
import java.time.temporal.{ChronoUnit, TemporalUnit}

trait RequestsParams {
  type RequestBuilder = SignRequest.Builder[AwsCredentials]
  lazy val validUntil: Instant = Instant.now().plus(amountToAdd, unit)
  def amountToAdd: Long
  def unit: TemporalUnit
  final def expired(): Boolean = Instant.now().isAfter(validUntil)
  def builder: RequestBuilder
}

class RequestsSignerParams(val credentials: AwsCredentials) extends RequestsParams {
  override val amountToAdd: Long = 60
  override val unit: TemporalUnit = ChronoUnit.MINUTES
  override val builder: RequestBuilder =
    SignRequest.builder[AwsCredentials](credentials)
      .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, "es") // "es" is the signing name for Elasticsearch
      .putProperty(AwsV4HttpSigner.REGION_NAME, Region.EU_WEST_2.id())
}

object RequestsSigner {
  private lazy val signer: AwsV4HttpSigner = AwsV4HttpSigner.create()

  def createSignedRequest(method: HttpMethod,
                          uri: URI,
                          path: String,
                          body: String,
                          params: RequestsParams): HttpRequest = {

    import scala.jdk.CollectionConverters._

    val uriBuilder = new URIBuilder(path)
    val httpMethod = SdkHttpMethod.fromValue(method.value)
    val builder = SdkHttpFullRequest
      .builder()
      .uri(uri)
      .encodedPath(uriBuilder.build().getRawPath)
      .method(httpMethod)

    uriBuilder.getQueryParams.asScala.foreach(param => builder.putRawQueryParameter(param.getName, param.getValue))

    val request = HttpRequest(method, path)
    request.headers.foreach(header => builder.putHeader(header.name(), header.value()))
    builder.contentStreamProvider(() => new ByteArrayInputStream(body.getBytes))
    val awsRequest = builder.build()

    val signRequest = params.builder
      .request(awsRequest)
      .payload(awsRequest.contentStreamProvider().orElseGet(() => () => new ByteArrayInputStream(Array[Byte]())))
      .build()

    val signedRequest = signer.sign(signRequest)

    val headers = signedRequest.request().headers.asScala.map {
      case (name, values) => RawHeader(name, values.asScala.mkString(","))
    }.toList

    val is = signedRequest.payload().orElseGet(() => () => new ByteArrayInputStream(Array[Byte]())).newStream()
    request.withHeadersAndEntity(headers, HttpEntity(ContentTypes.`application/json`, scala.io.Source.fromInputStream(is).mkString))
  }

}