package uk.gov.hmrc.nonrep.attachment.wiremockstubs

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, postRequestedFor, urlEqualTo}

import java.io.File
import java.nio.file.Files

trait DynamoDBWireMockStubs {
  this: WireMockSupport =>

  def successfulDynamoDB(): Unit =
    wireMockServer.stubFor(
      post(urlEqualTo(s"/dynamodb/"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody{
              Files.readAllBytes(new File(getClass.getClassLoader.getResource("dynamodb/successfulDynamoDB.json").getFile).toPath)
            }
        )
    )

  def emptyDynamoDB(): Unit =
    wireMockServer.stubFor(
      post(urlEqualTo(s"/dynamodb/"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(
              Files.readAllBytes(new File(getClass.getClassLoader.getResource("dynamodb/successfulDynamoDB.json").getFile).toPath)
            )
        )
    )

  def failedDynamoDB(): Unit =
    wireMockServer.stubFor(
      post(urlEqualTo(s"/dynamodb/"))
        .willReturn(
          aResponse()
            .withStatus(500)
            .withBody("Internal Server Error")
        )
    )

  def verifyDynamoDBCalled(times: Int): Unit =
    wireMockServer.verify(times, postRequestedFor(urlEqualTo(s"/dynamodb/")))
}
