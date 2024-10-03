package uk.gov.hmrc.nonrep.attachment
package service

import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.time.{Clock, Instant, ZoneId}

import org.apache.pekko.http.scaladsl.model.HttpMethods
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.auth.signer.internal.Aws4SignerUtils
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams
import software.amazon.awssdk.regions.Region

class RequestsSignerSpec extends BaseSpec {

  "request signer" should {
    import RequestsSigner._
    import TestServices._

    "create signed http request" in {

      val accessKeyId = "ASIAXXX"
      val service = "es"
      val region = Region.EU_WEST_2
      val signingClock = Clock.fixed(Instant.parse("2021-03-10T12:13:14.15Z"), ZoneId.of("Europe/London"))
      val credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, "xxx"))
      val signerParams = Aws4SignerParams.builder()
        .awsCredentials(credentials.resolveCredentials()).ensure
        .signingRegion(region).ensure
        .signingName(service).ensure
        .signingClockOverride(signingClock).ensure
        .build()

      val path = "/test"
      val body = """{"query": {"match_all":{}}"""
      val request = createSignedRequest(HttpMethods.POST, config.elasticSearchUri, path, body, signerParams)
      request.method shouldBe HttpMethods.POST
      request.uri.toString shouldBe path
      whenReady(entityToString(request.entity)) { res =>
        res shouldBe body
      }

      val expectedAuthHeader = s"AWS4-HMAC-SHA256 Credential=$accessKeyId/${Aws4SignerUtils.formatDateStamp(signingClock.millis())}/${region.id()}/$service/aws4_request, SignedHeaders=host;x-amz-date, Signature=bb03af1fe55354a99fc951b2c61afae153ea4faf6d4ee9a0eeaa1ed158c54485"

      request.headers.find(_.name() == "Host").get.value() shouldBe config.elasticSearchHost
      request.headers.find(_.name() == "X-Amz-Date").get.value() shouldBe "20210310T121314Z"
      request.headers.find(_.name() == "Authorization").get.value() shouldBe expectedAuthHeader
    }
  }

  "requests signer parameters class" should {
    val params = new RequestsSignerParams() {
      override val amountToAdd: Long = 1
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
