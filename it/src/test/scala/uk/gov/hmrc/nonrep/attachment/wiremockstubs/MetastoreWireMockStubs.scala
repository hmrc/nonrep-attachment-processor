package uk.gov.hmrc.nonrep.attachment.wiremockstubs

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, get, getRequestedFor, moreThanOrExactly, post, postRequestedFor, put, putRequestedFor, urlEqualTo, urlMatching, urlPathEqualTo}
import com.github.tomakehurst.wiremock.common.Json
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.{Scenario, StubMapping}
import org.scalatest.time.Span

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

trait MetastoreWireMockStubs {
  this: WireMockSupport =>
  
  def metastoreStoreMessage(bucket: String, key: String): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/$bucket/index/$key?refresh=false"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody("")
        )
    )
  }

  def metastoreStoreError(bucket: String, key: String, statusCode: Int, error: String): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/$bucket/index/$key?refresh=false"))
        .willReturn(
          aResponse()
            .withStatus(statusCode)
            .withHeader("Content-Type", "application/json")
            .withBody(s"""{"__type": $error, "message": "The specified queue does not exist."}""".stripMargin.getBytes(Charset.forName("UTF-8")))

        )
    )
  }

}
