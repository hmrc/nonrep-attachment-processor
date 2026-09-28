package uk.gov.hmrc.nonrep.attachment.wiremockstubs

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, post, postRequestedFor, urlEqualTo}

trait DynamoDBWireMockStubs {
  this: WireMockSupport =>

  def successfulDynamoDB(): Unit =
    wireMockServer.stubFor(
      post(urlEqualTo(s"/dynamodb/"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(""" {
                        |    "ConsumedCapacity": {
                        |        "CapacityUnits": 1,
                        |        "TableName": "Thread"
                        |    },
                        |    "Item": {
                        |        "Tags": {
                        |            "SS": ["Update","Multiple Items","HelpMe"]
                        |        },
                        |        "LastPostDateTime": {
                        |            "S": "201303190436"
                        |        },
                        |        "Message": {
                        |            "S": "I want to update multiple items in a single call. What's the best way to do that?"
                        |        }
                        |    }
                        |}""".stripMargin.getBytes())
        )
    )

  def emptyDynamoDB(): Unit =
    wireMockServer.stubFor(
      post(urlEqualTo(s"/dynamodb/"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(""" {
                        |    "ConsumedCapacity": {
                        |        "CapacityUnits": 1,
                        |        "TableName": "Thread"
                        |    }
                        |}""".stripMargin.getBytes())
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
