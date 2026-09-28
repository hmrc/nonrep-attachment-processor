package uk.gov.hmrc.nonrep.attachment.wiremockstubs

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, get, getRequestedFor, post, postRequestedFor, put, putRequestedFor, urlEqualTo, urlMatching, urlPathEqualTo}
import com.github.tomakehurst.wiremock.common.Json
import com.github.tomakehurst.wiremock.stubbing.{Scenario, StubMapping}
import org.scalatest.time.Span

import java.io.File
import java.nio.file.Files

trait SignWireMockStubs {
  this: WireMockSupport =>

  def signMessage(state: String = Scenario.STARTED, to:String = Scenario.STARTED): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/localhost/cades/cades-t"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Files.readAllBytes(new File(getClass.getClassLoader.getResource("attachments/0f0d6508-7f9f-11ec-b1fb-a732847931b5").getFile).toPath))
        )
    )
  }
  
  def signMessageError(statusCode: Int, state: String = Scenario.STARTED, to:String = Scenario.STARTED): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/localhost/cades/cades-t"))
        .willReturn(
          aResponse()
            .withStatus(statusCode)
            .withBody("")
        )
    )
  }
  
  def signMessageDelay(delay:Span, state: String = Scenario.STARTED, to:String = Scenario.STARTED): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/localhost/cades/cades-t"))
        .willReturn(
          aResponse()
            .withFixedDelay(delay.millisPart.toInt)
            .withStatus(200)
            .withBody("")
        )
    )
  }
  
}
