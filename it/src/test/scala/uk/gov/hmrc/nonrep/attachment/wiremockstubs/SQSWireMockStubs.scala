package uk.gov.hmrc.nonrep.attachment.wiremockstubs

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, equalTo, get, getRequestedFor, post, postRequestedFor, put, putRequestedFor, urlEqualTo, urlMatching, urlPathEqualTo}
import com.github.tomakehurst.wiremock.http.Fault
import com.github.tomakehurst.wiremock.stubbing.Scenario
import org.scalatest.time.Span

import java.nio.charset.Charset

trait SQSWireMockStubs {
  this: WireMockSupport =>

  // https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html

  /*
      curl --verbose -X POST -H 'Content-Type: application/x-amz-json-1.0' -H 'X-Amz-Target: AmazonSQS.ReceiveMessage'  -d '
      {
          "QueueUrl": "http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/local-nonrep-attachment-queue/",
          "MaxNumberOfMessages": 5,
          "VisibilityTimeout": 15,
          "AttributeNames": ["All"]
      }' http://sqs.eu-west-2.localhost.localstack.cloud:4566/000000000000/local-nonrep-attachment-queue
   */

  def sendSQSMessage(state: String = Scenario.STARTED, to:String = Scenario.STARTED): Unit = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/local-nonrep-attachment-queue/"))
        .inScenario("sqs-queue")
        .whenScenarioStateIs(state)
        .withHeader("X-Amz-Target", equalTo("AmazonSQS.ReceiveMessage"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(sqsMessage)
        )
        .willSetStateTo(to)
    )
  }

  def sendFaultSQSMessage(failed: Fault, state: String = Scenario.STARTED, to:String = Scenario.STARTED): Unit = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/local-nonrep-attachment-queue/"))
        .inScenario("sqs-queue")
        .whenScenarioStateIs(state)
        .withHeader("X-Amz-Target", equalTo("AmazonSQS.ReceiveMessage"))
        .willReturn(
          aResponse()
            .withFault(failed)
//            .withStatus(200)
//            .withHeader("Content-Type", "application/json")
//            .withBody(sqsMessage)
        )
        .willSetStateTo(to)
    )
  }

  def sendSlowSQSMessage(delay:Span, state: String = Scenario.STARTED, to:String = Scenario.STARTED): Unit = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/local-nonrep-attachment-queue/"))
        .inScenario("sqs-queue")
        .whenScenarioStateIs(state)
        .withHeader("X-Amz-Target", equalTo("AmazonSQS.ReceiveMessage"))
        .willReturn(
          aResponse()
            .withFixedDelay(delay.millisPart.toInt)
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(sqsMessage)
        )
        .willSetStateTo(to)
    )
  }

  def sendSQSErrorMessage(statusCode:Int, error:String, state: String = Scenario.STARTED, to:String = "sqs-error"): Unit = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/local-nonrep-attachment-queue/"))
        .inScenario("sqs-queue")
        .whenScenarioStateIs(state)
        .withHeader("X-Amz-Target", equalTo("AmazonSQS.ReceiveMessage"))
        .willReturn(
          aResponse()
            .withStatus(statusCode)
            .withHeader("Content-Type", "application/json")
            .withBody(s"""{"__type": $error, "message": "The specified queue does not exist."}""".stripMargin.getBytes(Charset.forName("UTF-8")))

        )
        .willSetStateTo(to)
    )
  }

  def noSQSMessage(state: String, to:String): Unit = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/local-nonrep-attachment-queue/"))
        .inScenario("sqs-queue")
        .whenScenarioStateIs(state)
        .withHeader("X-Amz-Target", equalTo("AmazonSQS.ReceiveMessage"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(sqsMessageEmpty)
        )
        .willSetStateTo(to)
    )
  }

  def sendInvalidSQSMessage(state: String = Scenario.STARTED, to: String = Scenario.STARTED): Unit = {
    wireMockServer.stubFor(
      post(urlEqualTo(s"/local-nonrep-attachment-queue/"))
        .inScenario("sqs-queue")
        .whenScenarioStateIs(state)
        .withHeader("X-Amz-Target", equalTo("AmazonSQS.ReceiveMessage"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody(sqsInvalidMessage)
        )
        .willSetStateTo(to)
    )
  }
  
  def sqsDeleteMessage(state: String = Scenario.STARTED, to:String = Scenario.STARTED) =
    wireMockServer.stubFor(
      post(urlPathEqualTo("/local-nonrep-attachment-queue/"))
        .inScenario("sqs-delete")
        .whenScenarioStateIs(state)
        .withHeader("X-Amz-Target", equalTo("AmazonSQS.DeleteMessage"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody("")
        )
        .willSetStateTo(to)
    )

  def sqsDeleteMessageError(status:Int, error:String, state: String = Scenario.STARTED, to:String = Scenario.STARTED) =
    wireMockServer.stubFor(
      post(urlPathEqualTo("/local-nonrep-attachment-queue/"))
        .inScenario("sqs-delete")
        .whenScenarioStateIs(state)
        .withHeader("X-Amz-Target", equalTo("AmazonSQS.DeleteMessage"))
        .willReturn(
          aResponse()
            .withStatus(status)
            .withHeader("Content-Type", "application/json")
            .withBody(s"""{"__type": $error, "message": "The specified queue does not exist."}""".stripMargin.getBytes(Charset.forName("UTF-8")))

        )
        .willSetStateTo(to)
    )

  def verifySqsDeleteMessage(times: Int = 1): Unit =
    wireMockServer.verify(times, postRequestedFor(
      urlPathEqualTo(s"/local-nonrep-attachment-queue/"))
      .withHeader("X-Amz-Target", equalTo("AmazonSQS.DeleteMessage"))
    )

  private def sqsMessage: String =
    """
      |{
      |	"Messages": [
      |		{
      |			"MessageId": "35d6df82-c194-46cb-9f38-dfcf26d996bf",
      |			"MD5OfBody": "6d2cdccc3529cff70852da0db8087be6",
      |			"Body": "{\n  \"Records\": [\n    {\n      \"eventVersion\": \"2.0\",\n      \"eventSource\": \"aws:s3\",\n      \"awsRegion\": \"eu-west-2\",\n      \"eventTime\": \"2018-07-17T14:08:56.784Z\",\n      \"eventName\": \"ObjectCreated:Put\",\n      \"userIdentity\": {\n        \"principalId\": \"AWS:AROAI6UKNMK6GNG3RQ4J6:adam-put2_p\"\n      },\n      \"requestParameters\": {\n        \"sourceIPAddress\": \"35.178.67.252\"\n      },\n      \"responseElements\": {\n        \"x-amz-request-id\": \"AEACEBA7C61C2BCE\",\n        \"x-amz-id-2\": \"KKUq2q4T+66NOwEqvAZxAH7HefNI/KdVVbVZxf0/qS8V4n4nmlINLkg86n2shIvvsGgjHGnAGTA=\"\n      },\n      \"s3\": {\n        \"s3SchemaVersion\": \"1.0\",\n        \"configurationId\": \"sns1\",\n        \"bucket\": {\n          \"name\": \"local-nonrep-submission-data\",\n          \"ownerIdentity\": {\n            \"principalId\": \"A202PFQUTJVUOI\"\n          },\n          \"arn\": \"arn:aws:s3:::adam1-nonrep-submission-data\"\n        },\n        \"object\": {\n          \"key\": \"d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip\",\n          \"size\": 10000,\n          \"eTag\": \"93579cc5c9c8246e7ad30f14b99ecb83\",\n          \"sequencer\": \"005B4DF878BDCFA069\"\n        }\n      }\n    }\n  ]\n}",
      |			"Attributes": {
      |				"SenderId": "000000000000",
      |				"SentTimestamp": "1787821078159",
      |				"AWSTraceHeader":
      |				"Root=1-c72fdcda-63b88064ca694a6f1d38b104;Parent=82648942b71714e2;Sampled=1",
      |				"ApproximateReceiveCount": "1", "ApproximateFirstReceiveTimestamp": "1787821084199"}, "ReceiptHandle": "Y2IwZmRkZTctYTJiOS00ZmQ5LWIyN2QtMTUxMGFlOTk5ZDQ0IGFybjphd3M6c3FzOmV1LXdlc3QtMjowMDAwMDAwMDAwMDA6bG9jYWwtbm9ucmVwLWF0dGFjaG1lbnQtcXVldWUgMzVkNmRmODItYzE5NC00NmNiLTlmMzgtZGZjZjI2ZDk5NmJmIDE3ODc4MjEwODQuMTk5OTUzNg=="
      |			}
      |		]
      |	}
      |
    |""".stripMargin

  private def sqsInvalidMessage: String =
    """
      |{
      |	"Messages": [
      |		{
      |			"MessageId": "35d6df82-c194-46cb-9f38-dfcf26d996bf",
      |			"MD5OfBody": "adcb2ee57632e998f6d9a738c320f613",
      |			"Body": "{\n  \"Records\": [\n    {\n      \"eventVersion\": \"2.0\",\n      \"eventSource\": \"aws:s3\",\n      \"awsRegion\": \"eu-west-2\",\n      \"eventTime\": \"2018-07-17T14:08:56.784Z\",\n      \"eventName\": \"ObjectCreated:Put\",\n      \"userIdentity\": {\n        \"principalId\": \"AWS:AROAI6UKNMK6GNG3RQ4J6:adam-put2_p\"\n      },\n      \"requestParameters\": {\n        \"sourceIPAddress\": \"35.178.67.252\"\n      },\n      \"responseElements\": {\n        \"x-amz-request-id\": \"AEACEBA7C61C2BCE\",\n        \"x-amz-id-2\": \"KKUq2q4T+66NOwEqvAZxAH7HefNI/KdVVbVZxf0/qS8V4n4nmlINLkg86n2shIvvsGgjHGnAGTA=\"\n      },\n      \"s3\": {\n        \"s3SchemaVersion\": \"1.0\",\n        \"configurationId\": \"sns1\",\n        \"bucket\": {\n          \"name\": \"local-nonrep-submission-data\",\n          \"ownerIdentity\": {\n            \"principalId\": \"A202PFQUTJVUOI\"\n          },\n          \"arn\": \"arn:aws:s3:::adam1-nonrep-submission-data\"\n        },\n        \"NOT-object\": {\n          \"key\": \"d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip\",\n          \"size\": 10000,\n          \"eTag\": \"93579cc5c9c8246e7ad30f14b99ecb83\",\n          \"sequencer\": \"005B4DF878BDCFA069\"\n        }\n      }\n    }\n  ]\n}",
      |			"Attributes": {
      |				"SenderId": "000000000000",
      |				"SentTimestamp": "1787821078159",
      |				"AWSTraceHeader":
      |				"Root=1-c72fdcda-63b88064ca694a6f1d38b104;Parent=82648942b71714e2;Sampled=1",
      |				"ApproximateReceiveCount": "1", "ApproximateFirstReceiveTimestamp": "1787821084199"}, "ReceiptHandle": "Y2IwZmRkZTctYTJiOS00ZmQ5LWIyN2QtMTUxMGFlOTk5ZDQ0IGFybjphd3M6c3FzOmV1LXdlc3QtMjowMDAwMDAwMDAwMDA6bG9jYWwtbm9ucmVwLWF0dGFjaG1lbnQtcXVldWUgMzVkNmRmODItYzE5NC00NmNiLTlmMzgtZGZjZjI2ZDk5NmJmIDE3ODc4MjEwODQuMTk5OTUzNg=="
      |			}
      |		]
      |	}
      |
    |""".stripMargin

  private def sqsMessageEmpty: String =
    """{}""".stripMargin
}
