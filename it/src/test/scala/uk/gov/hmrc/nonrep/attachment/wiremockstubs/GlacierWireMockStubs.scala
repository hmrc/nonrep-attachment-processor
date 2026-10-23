package uk.gov.hmrc.nonrep.attachment.wiremockstubs

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, get, getRequestedFor, moreThanOrExactly, post, postRequestedFor, put, putRequestedFor, urlEqualTo, urlMatching, urlPathEqualTo}
import com.github.tomakehurst.wiremock.common.Json
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.{Scenario, StubMapping}
import org.scalatest.time.Span


trait GlacierWireMockStubs {
  this: WireMockSupport =>

  // https://docs.aws.amazon.com/amazonglacier/latest/dev/api-archive-post.html
  // errors:  https://docs.aws.amazon.com/amazonglacier/latest/dev/api-error-responses.html
  def glacierStore(vault:String, state: String = Scenario.STARTED, to:String = Scenario.STARTED): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/glacier/-/vaults/$vault/archives"))
        .inScenario("glacier-msg")
        .whenScenarioStateIs(state)
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("")
        )
        .willSetStateTo(to)
    )
  }

  def glacierStoreError(vault: String, statusCode:Int, errMsg:String): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/glacier/-/vaults/$vault/archives"))
        .willReturn(
          aResponse()
            .withStatus(statusCode)
            .withHeader("Content-Type", "application/json")
            .withHeader("x-amzn-RequestId", "AAABBeC9Zw0rp_5D0L8VfB3FA_WlTupqTKAUehMcPhdgni0")
            .withBody(badRequest(errMsg))
        )
    )
  }

  def glacierStoreFault(vault: String, fault:Fault): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/glacier/-/vaults/$vault/archives"))
        .willReturn(
          aResponse()
            .withFault(fault)
        )
    )
  }

  def glacierStoreTimeout(vault: String, delay:Span, state: String = Scenario.STARTED, to:String = Scenario.STARTED): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/glacier/-/vaults/$vault/archives"))
        .inScenario("glacier-msg")
        .whenScenarioStateIs(state)
        .willReturn(
          aResponse()
            .withFixedDelay(delay.millisPart.toInt)
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("")
        )
        .willSetStateTo(to)
    )
  }

  def glacierStoreReset(vault: String): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/glacier/-/vaults/$vault/archives"))
        .willReturn(
          aResponse()
            .withFault(Fault.CONNECTION_RESET_BY_PEER)
        )
    )
  }

  def glacierStoreEmptyResponse(vault: String): StubMapping = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/glacier/-/vaults/$vault/archives"))
        .willReturn(
          aResponse()
            .withFault(Fault.EMPTY_RESPONSE)
        )
    )
  }

  val vaultList:String =
    """
      |{
      |  "Marker": null,
      |  "VaultList": [
      |   {
      |    "CreationDate": "2012-03-16T22:22:47.214Z",
      |    "LastInventoryDate": "2012-03-21T22:06:51.218Z",
      |    "NumberOfArchives": 2,
      |    "SizeInBytes": 12334,
      |    "VaultARN": "arn:aws:glacier:us-west-2:012345678901:vaults/examplevault1",
      |    "VaultName": "examplevault1"
      |   },
      |   {
      |    "CreationDate": "2012-03-19T22:06:51.218Z",
      |    "LastInventoryDate": "2012-03-21T22:06:51.218Z",
      |    "NumberOfArchives": 0,
      |    "SizeInBytes": 0,
      |    "VaultARN": "arn:aws:glacier:us-west-2:012345678901:vaults/examplevault2",
      |    "VaultName": "examplevault2"
      |   },
      |   {
      |    "CreationDate": "2012-03-19T22:06:51.218Z",
      |    "LastInventoryDate": "2012-03-25T12:14:31.121Z",
      |    "NumberOfArchives": 0,
      |    "SizeInBytes": 0,
      |    "VaultARN": "arn:aws:glacier:us-west-2:012345678901:vaults/examplevault3",
      |    "VaultName": "examplevault3"
      |   }
      |  ]
      |}
      |""".stripMargin

  def verifyGlacierStoreCheck(times:Int = 1, vault:String): Unit =
    wireMockServer.verify(moreThanOrExactly(times), postRequestedFor(urlEqualTo(s"/glacier/-/vaults/$vault/archives")))

  def badRequest(errMsg:String):String =
    s"""
      |{
      |  "code": "${errMsg}",
      |  "message": "The job status code is not valid: finished",
      |  "type: "Client"
      |}
      |""".stripMargin
  
}
