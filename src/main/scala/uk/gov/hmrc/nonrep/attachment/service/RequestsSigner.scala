package uk.gov.hmrc.nonrep.attachment
package service

import java.io.ByteArrayInputStream
import java.net.URI

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethod, HttpRequest}
import org.apache.http.client.utils.URIBuilder
import software.amazon.awssdk.auth.signer.Aws4Signer
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams
import software.amazon.awssdk.http.{SdkHttpFullRequest, SdkHttpMethod}

object RequestsSigner {
  private lazy val signer = Aws4Signer.create()

  implicit class EnsureAws4SignerParamsBuilderType(any: Any) {
    def ensure: Aws4SignerParams.Builder[_] = any match {
      case builder: Aws4SignerParams.Builder[_] => builder
      case _ => throw new IllegalStateException("This method is intended to work with Aws4SignerParams.Builder only")
    }
  }

  def createSignedRequest(method: HttpMethod,
                          uri: URI,
                          path: String,
                          body: String,
                          params: Aws4SignerParams): HttpRequest = {

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

    val signedRequest = signer.sign(builder.build(), params)

    val headers = signedRequest.headers.asScala.map {
      case (name, values) => RawHeader(name, values.asScala.mkString(","))
    }.toList

    val is = signedRequest.contentStreamProvider().orElseGet(() => () => new ByteArrayInputStream(Array[Byte]())).newStream()
    request.withHeadersAndEntity(headers, HttpEntity(ContentTypes.`application/json`, scala.io.Source.fromInputStream(is).mkString))

  }
}