package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.http.scaladsl.model.HttpMethods
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.auth.signer.internal.Aws4SignerUtils
import software.amazon.awssdk.http.auth.aws.signer.{AwsV4FamilyHttpSigner, AwsV4HttpSigner}
import software.amazon.awssdk.http.auth.spi.signer.HttpSigner
import software.amazon.awssdk.http.{SdkHttpFullRequest, SdkHttpMethod}
import software.amazon.awssdk.regions.Region

import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.time.{Clock, Instant, ZoneId}

class RequestsSignerSpec extends BaseSpec {

  "request signer" should {
    import RequestsSigner.*
    import TestServices.*

    "create signed http request" in {

      val accessKeyId  = "ASIAXXX"
      val service      = "es"
      val region       = Region.EU_WEST_2
      val signingClock = Clock.fixed(Instant.parse("2021-03-10T12:13:14.15Z"), ZoneId.of("Europe/London"))
      val credentials  = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, "xxx"))
      val signerParams = new RequestsSignerParams(credentials.resolveCredentials())

      signerParams.builder
        .putProperty(AwsV4FamilyHttpSigner.SERVICE_SIGNING_NAME, service)
        .putProperty(AwsV4HttpSigner.REGION_NAME, region.id())
        .putProperty(HttpSigner.SIGNING_CLOCK, signingClock)

      val path    = "/test"
      val body    = """{"query": {"match_all":{}}"""
      val request = createSignedRequest(HttpMethods.POST, config.elasticSearchUri, path, body, signerParams)
      request.method       shouldBe HttpMethods.POST
      request.uri.toString shouldBe path
      whenReady(entityToString(request.entity)) { res =>
        res shouldBe body
      }

      val expectedAuthHeader =
        s"AWS4-HMAC-SHA256 Credential=$accessKeyId/${Aws4SignerUtils.formatDateStamp(signingClock.millis())}/${region.id()}/$service/aws4_request, SignedHeaders=host;x-amz-content-sha256;x-amz-date, Signature=6e37481b369aa5053b559c22c33bded0fe80089c0ffa3a8f0247ab08994fe8f9"

      request.headers.find(_.name() == "Host").get.value()          shouldBe config.elasticSearchHost
      request.headers.find(_.name() == "X-Amz-Date").get.value()    shouldBe "20210310T121314Z"
      request.headers.find(_.name() == "Authorization").get.value() shouldBe expectedAuthHeader
    }
  }

  "requests signer parameters class" should {
    val accessKeyId = "ASIAXXX"
    val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, "xxx"))
    val params      = new RequestsSignerParams(credentials.resolveCredentials()) {
      override val amountToAdd: Long  = 1
      override val unit: TemporalUnit = ChronoUnit.SECONDS
    }
    "not report expiry before defined time" in {
      params.expired() shouldBe false
    }
    "report expiry after defined time" in {
      Thread.sleep(1500)
      params.expired() shouldBe true
    }
  }
}
