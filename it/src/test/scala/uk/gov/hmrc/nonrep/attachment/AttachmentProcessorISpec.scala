package uk.gov.hmrc.nonrep.attachment

import com.github.tomakehurst.wiremock.http.Fault
import org.scalatest.time.Span
import uk.gov.hmrc.nonrep.attachment.utils.TestNonrepMicroservice
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalactic.source
import org.scalatest.{AppendedClues, Suite}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import org.scalatest.concurrent.Eventually.*
import org.scalatest.time.SpanSugar.convertIntToGrainOfTime
import uk.gov.hmrc.nonrep.attachment.wiremockstubs.{DynamoDBWireMockStubs, GlacierWireMockStubs, MetastoreWireMockStubs, S3WireMockStubs, SQSWireMockStubs, SignWireMockStubs, WireMockSupport}
import scala.collection.convert.AsScalaConverters

trait AsyncBaseSpec extends AnyWordSpec, ScalaFutures, ScalatestRouteTest, Matchers

trait WireMockStubsList extends SQSWireMockStubs, S3WireMockStubs, SignWireMockStubs, DynamoDBWireMockStubs, GlacierWireMockStubs, MetastoreWireMockStubs {
  this: WireMockSupport =>
}

class AttachmentProcessorISpec extends AsyncBaseSpec, WireMockSupport, WireMockStubsList, AsScalaConverters, AppendedClues {
  this: Suite =>

  val mockConfig: ServiceConfig = new ServiceConfig(8099) {
    override val queueUrl: String = s"http://localhost:$wireMockPort/local-nonrep-attachment-queue"
    override val isSignaturesServiceSecure: Boolean = false
    override val signaturesServiceHost: String = "localhost"
    override val signaturesServicePort: Int = wireMockPort
    override val glacierUrl: String = s"http://localhost:$wireMockPort/glacier"
    override val elasticSearchHost: String = "localhost"
    override val elasticSearchPort: Int = wireMockPort
  }

  var testKit: ActorTestKit = null

  override def beforeEach(): Unit = {
    super.beforeEach()
    testKit = ActorTestKit(actorSystemNameFrom(getClass), mockConfig.config)
    wireMockServer.resetAll()
  }

  override def afterEach(): Unit = {
    super.afterEach()
    testKit.system.terminate()  // this will stop NonrepMicroservice in each test
  }

  val commonErrors: Seq[(Int, String, String)] = List(
    (400, "AccessDeniedException", "Bad Request"),
    (400, "IncompleteSignature", "Bad Request"),
    (500, "InternalFailure", "Internal Server Error"),
    (400, "InvalidAction", "Bad Request"),
    (403, "InvalidClientTokenId", "Forbidden"),
    (400, "InvalidParameterCombination", "Bad Request"),
    (400, "InvalidParameterValue", "Bad Request"),
    (400, "InvalidQueryParameter", "Bad Request"),
    (404, "MalformedQueryString", "Not Found"),
    (400, "MissingAction", "Bad Request"),
    (400, "MissingAuthenticationToken", "Bad Request"),
    (400, "MissingParameter", "Bad Request"),
    (400, "NotAuthorized", "Bad Request"),
    (403, "OptInRequired", "Forbidden"),
    (400, "RequestExpired", "Bad Request"),
    (503, "ServiceUnavailable", "Service Unavailable"),
    (403, "ThrottlingException", "Forbidden"),
    (400, "ValidationError", "Bad Request")
  )

  // MALFORMED_RESPONSE_CHUNK gives OK and then garbage (see https://wiremock.org/2.x/docs/simulating-faults/)
  // we only look for the OK and ignore the body meaning it's not an error
  val faultList: Array[Fault] =  Fault.values.filterNot( _ == Fault.MALFORMED_RESPONSE_CHUNK)

  /*
  This is a default 'happy' journey for a single message.
  Override the required step to create the required change in the journey
   */
  trait StreamMessageJourney(attachmentId:String = "d9b3f2f3-32e1-4903-b812-a64c2a045c61") {
    def getMessages(): Unit = {sendSQSMessage(to="no-msg"); noSQSMessage(state="no-msg", to="no-msg")}
    def parseMessage(): Unit = {}
    def downloadBundle(): Unit = {successfulGetAttachment(attachmentId)}
    def unpackBundle(): Unit = {}
    def signAttachment(): Unit = {signMessage()}
    def repackBundle(): Unit = {}
    def archiveBundle(): Unit = {glacierStore("local-vat-registration-2026")}
    def updateMetastore(): Unit = {metastoreStoreMessage("vat-registration-attachments", attachmentId)}
    def deleteMessage(): Unit = {sqsDeleteMessage()}
    def deleteBundle(): Unit = {s3DeleteMessage("local-nonrep-attachment-data", attachmentId+".zip")}
    def recordProcessingTime(): Unit = {}

    def createMessageJourney(): Unit = {
      getMessages()
      parseMessage()
      downloadBundle()
      unpackBundle()
      signAttachment()
      repackBundle()
      archiveBundle()
      updateMetastore()
      deleteMessage()
      deleteBundle()
      recordProcessingTime()
    }
  }

  "Check a good message(s) can be processed correctly" should {

    "Check a single message processed ok" in new StreamMessageJourney{
      createMessageJourney()

      private val service = createNonrepMicroservice(testKit)

      waitForNMessages(1)(service)

      service.msgSuccessCount shouldBe 1 withClue ("incorrect successCount")
      verifyDeleteMessage(1, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

      service.failedMsgList.length shouldBe 0
    }

    "Check two messages can be processed ok" in new StreamMessageJourney {
      override def getMessages(): Unit = {
        sendSQSMessage(to = "msg-2")
        sendSQSMessage(state = "msg-2", to = "no-msg")
        noSQSMessage(state = "no-msg", to = "no-msg")
      }
      createMessageJourney()

      private val service = createNonrepMicroservice(testKit)

      waitForNMessages(2)(service)

      service.msgSuccessCount shouldBe 2 withClue (s"incorrect successCount")
      verifyDeleteMessage(2, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")
    }
  }

  "getMessage (SQS)" should {

    // https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_ReceiveMessage.html
    "log common (4xx) error checks and recover on next message" should {
      for (statusCode, errMsg, statusCodeText) <- commonErrors.filterNot( err => is5xx( err._1)) do
        s"${errMsg}(${statusCode}) SQS request error (error msg followed by ok msg)" in new StreamMessageJourney {
          override def getMessages(): Unit = {
            sendSQSErrorMessage(statusCode, errMsg, to = "msg-2")
            sendSQSMessage(state = "msg-2", to = "no-msg")
            noSQSMessage(state = "no-msg", to = "no-msg")
          }
          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(2)(service)

          service.msgSuccessCount shouldBe 1 withClue ("incorrect successCount")
          service.msgFailedCount shouldBe 1 withClue ("incorrect failedCount")

          service.failedMsgList.length shouldBe 1 withClue( service.failedMsgList.mkString(", "))
        }

      "5xx errors are handled by AWS api internally and recover on next message" should {
        for (statusCode, errMsg, statusCodeText) <- commonErrors.filter(err => is5xx(err._1)) do
          s"${errMsg}(${statusCode}) SQS request error (error msg followed by ok msg)" in new StreamMessageJourney {
            override def getMessages(): Unit = {
              sendSQSErrorMessage(statusCode, errMsg, to = "msg-2")
              sendSQSMessage(state = "msg-2", to = "no-msg")
              noSQSMessage(state = "no-msg", to = "no-msg")
            }

            createMessageJourney()
            private val service = createNonrepMicroservice(testKit)
            waitForNMessages(1)(service)

            service.msgSuccessCount shouldBe 1 withClue ("incorrect successCount")
            service.msgFailedCount shouldBe 0 withClue ("incorrect failedCount")

            service.failedMsgList.length shouldBe 0 withClue (service.failedMsgList.mkString(", "))

          }
      }
    }

    "SQS specific errors" should {
      val normalErrors = List(
        (400, "InvalidAddress"),
        (400, "InvalidSecurity"),
        (400, "KmsAccessDenied"),
        (400, "KmsDisabled"),
        (400, "KmsInvalidKeyUsage"),
        (400, "KmsInvalidState"),
        (400, "KmsNotFound"),
        (400, "KmsOptInRequired"),
        (400, "KmsThrottled"),
        (400, "OverLimit"),
        (400, "QueueDoesNotExist"),
        (400, "RequestThrottled"),
        (400, "UnsupportedOperation")
      )
      "pass normal error checks" should {
        for (statusCode, errMsg) <- normalErrors do
          s"${errMsg}(${statusCode}) SQS request error (error msg followed by ok msg)" in new StreamMessageJourney {

            override def getMessages(): Unit = {
              sendSQSErrorMessage(statusCode, errMsg, to = "msg-2")
              sendSQSMessage(state = "msg-2", to = "no-msg")
              noSQSMessage(state = "no-msg", to = "no-msg")
            }

            createMessageJourney()

            private val service = createNonrepMicroservice(testKit)

            waitForNMessages(2)(service)

            service.msgSuccessCount shouldBe 1 withClue ("incorrect successCount")
            service.msgFailedCount shouldBe 1 withClue ("incorrect failedCount")
            verifyDeleteMessage(1, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

            service.failedMsgMessages shouldBe List("Get SQS message failure: Service returned HTTP status code 400 (Service: Sqs, Status Code: 400, Request ID: null) (SDK Attempt Count: 1)")
          }
      }

    }
    "SQS other error test" should {
      "timeout" in new StreamMessageJourney {

        override def getMessages(): Unit = {
          sendSlowSQSMessage(35.seconds, to = "msg-2")
          sendSQSMessage(state = "msg-2", to = "no-msg")
          noSQSMessage(state = "no-msg", to = "no-msg")
        }

        createMessageJourney()
        private val service = createNonrepMicroservice(testKit)

        eventually(timeout(40.seconds), interval(1.second)) {
          val count = service.msgCountTotal
          count should be > 0
        }

        service.msgFailedCount shouldBe 0 withClue ("incorrect failedCount")
        service.msgSuccessCount shouldBe 1 withClue ("incorrect successCount")
      }

      "invalid SQS json message (delete sqs message should be called)" in new StreamMessageJourney {
        override def getMessages(): Unit = {
          sendInvalidSQSMessage(to = "no-msg")
          noSQSMessage(state = "no-msg", to = "no-msg")
        }

        createMessageJourney()

        private val service = createNonrepMicroservice(testKit)

        waitForNMessages(1)(service)
        service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")
        verifySqsDeleteMessage(1)

        service.failedMsgMessages shouldBe List(
          """Parsing SQS message failure {
            |  "Records": [
            |    {
            |      "eventVersion": "2.0",
            |      "eventSource": "aws:s3",
            |      "awsRegion": "eu-west-2",
            |      "eventTime": "2018-07-17T14:08:56.784Z",
            |      "eventName": "ObjectCreated:Put",
            |      "userIdentity": {
            |        "principalId": "AWS:AROAI6UKNMK6GNG3RQ4J6:adam-put2_p"
            |      },
            |      "requestParameters": {
            |        "sourceIPAddress": "35.178.67.252"
            |      },
            |      "responseElements": {
            |        "x-amz-request-id": "AEACEBA7C61C2BCE",
            |        "x-amz-id-2": "KKUq2q4T+66NOwEqvAZxAH7HefNI/KdVVbVZxf0/qS8V4n4nmlINLkg86n2shIvvsGgjHGnAGTA="
            |      },
            |      "s3": {
            |        "s3SchemaVersion": "1.0",
            |        "configurationId": "sns1",
            |        "bucket": {
            |          "name": "local-nonrep-submission-data",
            |          "ownerIdentity": {
            |            "principalId": "A202PFQUTJVUOI"
            |          },
            |          "arn": "arn:aws:s3:::adam1-nonrep-submission-data"
            |        },
            |        "NOT-object": {
            |          "key": "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip",
            |          "size": 10000,
            |          "eTag": "93579cc5c9c8246e7ad30f14b99ecb83",
            |          "sequencer": "005B4DF878BDCFA069"
            |        }
            |      }
            |    }
            |  ]
            |}""".stripMargin

        )
      }

      "Fault check" should {
        s"${Fault.CONNECTION_RESET_BY_PEER} Failed SQS msg" in new StreamMessageJourney {
          override def getMessages(): Unit = {
            sendFaultSQSMessage(Fault.CONNECTION_RESET_BY_PEER)
          }

          createMessageJourney()

          private val service = createNonrepMicroservice(testKit)

          waitForNMessages(1)(service)

          service.msgFailedCount shouldBe 1 withClue ("failedCount")
          service.msgSuccessCount shouldBe 0 withClue ("successCount")

          service.failedMsgMessages shouldBe List("Get SQS message failure: Unable to execute HTTP request: Connection reset (SDK Attempt Count: 4)")
        }

        s"${Fault.EMPTY_RESPONSE} Failed SQS msg" in new StreamMessageJourney {
          override def getMessages(): Unit = {
            sendFaultSQSMessage(Fault.EMPTY_RESPONSE)
          }

          createMessageJourney()

          private val service = createNonrepMicroservice(testKit)

          waitForNMessages(1)(service)

          service.msgFailedCount shouldBe 1 withClue ("failedCount")
          service.msgSuccessCount shouldBe 0 withClue ("successCount")

          service.failedMsgMessages.headOption.getOrElse("") should startWith ("Get SQS message failure: Unable to execute HTTP request: The connection was closed during the request. The request will usually succeed on a retry, but if it does not: consider disabling any proxies you have configured, enabling debug logging, or performing a TCP dump to identify the root cause. If this is a streaming operation, validate that data is being read or written in a timely manner.")
        }

        s"${Fault.RANDOM_DATA_THEN_CLOSE} Failed SQS msg" in new StreamMessageJourney {
          override def getMessages(): Unit = {
            sendFaultSQSMessage(Fault.RANDOM_DATA_THEN_CLOSE)
          }

          createMessageJourney()

          private val service = createNonrepMicroservice(testKit)

          waitForNMessages(1)(service)

          service.msgFailedCount shouldBe 1 withClue ("failedCount")
          service.msgSuccessCount shouldBe 0 withClue ("successCount")

          service.failedMsgMessages.headOption.getOrElse("") should startWith ("Get SQS message failure: Unable to execute HTTP request: The connection was closed during the request. The request will usually succeed on a retry, but if it does not: consider disabling any proxies you have configured, enabling debug logging, or performing a TCP dump to identify the root cause. If this is a streaming operation, validate that data is being read or written in a timely manner.")
        }
      }

    }
  }

  "downloadAttachment (S3)" should {
    "s3 missing attachment" in new StreamMessageJourney {

      override def downloadBundle(): Unit = {
        failedGetAttachment("d9b3f2f3-32e1-4903-b812-a64c2a045c61")
      }

      createMessageJourney()
      private val service = createNonrepMicroservice(testKit)
      waitForNMessages(1)(service)
      service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

      service.failedMsgMessages shouldBe List("failed to download d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip attachment bundle from s3 local-nonrep-attachment-data")
    }

    "s3 timeout" in new StreamMessageJourney {
      override def downloadBundle(): Unit = {
        failedGetAttachmentTimeout("d9b3f2f3-32e1-4903-b812-a64c2a045c61", 35.seconds)
      }

      createMessageJourney()
      private val service = createNonrepMicroservice(testKit)

      waitForNMessages(1, 40.seconds)(service)

      service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

      // s3DeleteMessage should NOT be called
      verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

      service.failedMsgMessages shouldBe List("failed to download d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip attachment bundle from s3 local-nonrep-attachment-data")
    }

    "s3 common errors" should {
      for ((statusCode, errMsg, statusCodeText) <- commonErrors) {
        s"${errMsg}(${statusCode}) S3 request error" in new StreamMessageJourney {

          override def downloadBundle(): Unit = {
            failedErrorGetAttachment("d9b3f2f3-32e1-4903-b812-a64c2a045c61", statusCode, errMsg)
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)
          service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

          verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

          service.failedMsgMessages shouldBe List("failed to download d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip attachment bundle from s3 local-nonrep-attachment-data")
        }
      }
    }
  }

  "signAttachment" should {
    "pass common error checks" should {
      for (statusCode, errMsg, statusCodeText) <- commonErrors do
        s"${errMsg}(${statusCode}) sign failed" in new StreamMessageJourney {
          override def signAttachment(): Unit = {
            signMessageError(statusCode)
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)

          service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

          verifyGlacierStoreCheck(0, "local-vat-registration-2026")
          verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

          service.failedMsgMessages shouldBe List(s"Response status $statusCode $statusCodeText from signatures service localhost")
        }
    }

    "failed save with timeout" in new StreamMessageJourney {
      private val delay = 2.minutes

      override def signAttachment(): Unit = {
        signMessageDelay(delay)
      }

      createMessageJourney()
      private val service = createNonrepMicroservice(testKit)
      waitForNMessages(1, delay + 10.seconds)(service)
      service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

      verifyGlacierStoreCheck(0, "local-vat-registration-2026")
      verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

      service.failedMsgMessages shouldBe List("Failure connection to localhost with TCP idle-timeout encountered on connection to [localhost/<unresolved>:9008], no bytes passed in the last 60 seconds")
    }

  }

  "glacierStoreMessage (Glacier)" should {
    // https://docs.aws.amazon.com/amazonglacier/latest/dev/api-error-responses.html
    val glacierErrors: Seq[(Int, String)] = List(
      (403, "AccessDeniedException"),
      (400, "BadRequest"),
      (403, "ExpiredTokenException"),
      (503, "InsufficientCapacityException"),
      (400, "InvalidParameterValueException"),
      (403, "InvalidSignatureException"),
      (400, "LimitExceededException"),
      (400, "MissingAuthenticationTokenException"),
      (400, "MissingParameterValueException"),
      (400, "PolicyEnforcedException"),
      (404, "ResourceNotFoundException"),
      (408, "RequestTimeoutException"),
      (400, "SerializationException"),
      (500, "ServiceUnavailableException"),
      (400, "ThrottlingException"),
      (400, "UnrecognizedClientException")
    )

    "glacier common errors" should {
      for ((statusCode, errMsg) <- glacierErrors) {
        s"${errMsg}(${statusCode}) S3 request error" in new StreamMessageJourney {
          override def archiveBundle(): Unit = {
            glacierStoreError("local-vat-registration-2026", statusCode, errMsg)
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)

          service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

          private val callCount = if is5xx(statusCode) then 4 else 1
          verifyGlacierStoreCheck(callCount, "local-vat-registration-2026")
          // s3DeleteMessage should NOT be called
          verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

          service.failedMsgMessages shouldBe List("Error uploading attachment AttachmentContent(attachmentId:d9b3f2f3-32e1-4903-b812-a64c2a045c61, submissionId:eed095f9-7cd5-4a58-b74e-906c8d8807b5, notableEvent:vat-registration, s3ObjectKey:d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip, attachmentSize:None) to glacier local-vat-registration-2026")
        }
      }
    }

    "glacier vault no found" in new StreamMessageJourney {
      // TODO GlacierService.eventuallyArchive has 'case ResourceNotFoundException' but this request is generating
      //  a more generic GlacierException, needs more investigation
      override def archiveBundle(): Unit = {
        glacierStoreError("local-vat-registration-2026", 404, "ResourceNotFoundException")
      }

      createMessageJourney()
      private val service = createNonrepMicroservice(testKit)
      waitForNMessages(1)(service)

      service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")
      verifyGlacierStoreCheck(1, "local-vat-registration-2026")
      // s3DeleteMessage should NOT be called
      verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

      service.failedMsgMessages shouldBe List("Error uploading attachment AttachmentContent(attachmentId:d9b3f2f3-32e1-4903-b812-a64c2a045c61, submissionId:eed095f9-7cd5-4a58-b74e-906c8d8807b5, notableEvent:vat-registration, s3ObjectKey:d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip, attachmentSize:None) to glacier local-vat-registration-2026")
    }

    "pass common error checks" should {
      for (statusCode, errMsg, statusCodeText) <- commonErrors do
        s"${errMsg}(${statusCode}) failed save to glacier" in new StreamMessageJourney {
          override def archiveBundle(): Unit = {
            glacierStoreError("local-vat-registration-2026", statusCode, errMsg)
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)

          service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")
          verifyGlacierStoreCheck(1, "local-vat-registration-2026")
          // s3DeleteMessage should NOT be called
          verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

          service.failedMsgMessages shouldBe List("Error uploading attachment AttachmentContent(attachmentId:d9b3f2f3-32e1-4903-b812-a64c2a045c61, submissionId:eed095f9-7cd5-4a58-b74e-906c8d8807b5, notableEvent:vat-registration, s3ObjectKey:d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip, attachmentSize:None) to glacier local-vat-registration-2026")
        }
    }

    "Fault check " should {
      // MALFORMED_RESPONSE_CHUNK is OK with garbage body. body not used, so ignore test
      for fault <- faultList do
        s"${fault.name} Fault " in new StreamMessageJourney {
          override def archiveBundle(): Unit = {
            glacierStoreFault("local-vat-registration-2026", fault)
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)

          service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")
          verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

          service.failedMsgMessages shouldBe List("Error uploading attachment AttachmentContent(attachmentId:d9b3f2f3-32e1-4903-b812-a64c2a045c61, submissionId:eed095f9-7cd5-4a58-b74e-906c8d8807b5, notableEvent:vat-registration, s3ObjectKey:d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip, attachmentSize:None) to glacier local-vat-registration-2026")
        }
    }

    "Timeout and recover on retry" in new StreamMessageJourney  {
      override def archiveBundle(): Unit = {
        glacierStoreTimeout("local-vat-registration-2026", 30.seconds, to="store-ok")
        glacierStore("local-vat-registration-2026", state="store-ok", to="store-ok")
      }

      createMessageJourney()
      private val service = createNonrepMicroservice(testKit)
      waitForNMessages(1, 35.seconds)(service)

      service.msgSuccessCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgFailedCount}")
      service.msgFailedCount shouldBe 0 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

      verifyGlacierStoreCheck(1, "local-vat-registration-2026")
      verifyDeleteMessage(1, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")
    }

    "Timeout on save, two msgs" in new StreamMessageJourney {
      override def getMessages(): Unit = {
        sendSQSMessage(to = "msg-2")
        sendSQSMessage(state = "msg-2", to = "no-msg")
        noSQSMessage(state = "no-msg", to = "no-msg")
      }
      override def archiveBundle(): Unit = {
        glacierStoreTimeout("local-vat-registration-2026", 35.seconds, to = "ok-msg")
        glacierStoreTimeout("local-vat-registration-2026", 1.seconds, state = "ok-msg", to = "ok-msg")
      }

      createMessageJourney()
      private val service = createNonrepMicroservice(testKit)
      Thread.sleep(10.seconds.millisPart)
      waitForNMessages(2, 70.seconds)(service)

      service.msgFailedCount shouldBe 0 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")
      service.msgSuccessCount shouldBe 2 withClue (s"incorrect successCount  Success:${service.msgFailedCount}")

      verifyGlacierStoreCheck(2, "local-vat-registration-2026")
      verifyDeleteMessage(2, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")
    }

    "Connection reset by peer" in new StreamMessageJourney {
      override def archiveBundle(): Unit = {
        glacierStoreReset("local-vat-registration-2026")
      }

      createMessageJourney()
      private val service = createNonrepMicroservice(testKit)

      waitForNMessages(1)(service)
      service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")
      service.msgSuccessCount shouldBe 0 withClue (s"incorrect successCount  Success:${service.msgFailedCount}")

      service.failedMsgMessages shouldBe List("Error uploading attachment AttachmentContent(attachmentId:d9b3f2f3-32e1-4903-b812-a64c2a045c61, submissionId:eed095f9-7cd5-4a58-b74e-906c8d8807b5, notableEvent:vat-registration, s3ObjectKey:d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip, attachmentSize:None) to glacier local-vat-registration-2026")
    }

    "completely empty response" in new StreamMessageJourney {
      override def archiveBundle(): Unit = {
        glacierStoreEmptyResponse("local-vat-registration-2026")
      }

      createMessageJourney()
      private val service = createNonrepMicroservice(testKit)
      waitForNMessages(1)(service)

      service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

      // has 5 auto retries
      verifyGlacierStoreCheck(4, "local-vat-registration-2026")
      // s3DeleteMessage should NOT be called
      verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

      service.failedMsgMessages shouldBe List("Error uploading attachment AttachmentContent(attachmentId:d9b3f2f3-32e1-4903-b812-a64c2a045c61, submissionId:eed095f9-7cd5-4a58-b74e-906c8d8807b5, notableEvent:vat-registration, s3ObjectKey:d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip, attachmentSize:None) to glacier local-vat-registration-2026")
    }

  }

  "updateMetastore" should {
    "pass common error checks" should {
      for (statusCode, errMsg, statusCodeText) <- commonErrors do
        s"${errMsg}(${statusCode}) delete SQS message" in new StreamMessageJourney {
          override def updateMetastore(): Unit = {
            metastoreStoreError("vat-registration-attachments", "d9b3f2f3-32e1-4903-b812-a64c2a045c61", statusCode, errMsg)
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)

          service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

          // check delete was NOT called
          verifySqsDeleteMessage(0)
          verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

          service.failedMsgMessages shouldBe List(s"Response status $statusCode $statusCodeText from ES service localhost")
        }
    }
  }

  // https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_DeleteMessage.html
  val deleteMessageErrors: Seq[(Int, String, String)] = List(
    (400, "InvalidAddress", ""),
    (400, "InvalidIdFormat", ""),
    (400, "InvalidSecurity", ""),
    (400, "QueueDoesNotExist", ""),
    (400, "ReceiptHandleIsInvalid", ""),
    (400, "RequestThrottled", ""),
    (400, "UnsupportedOperation", "")
  )

  "deleteMessage (SQS)" should {
    "deleteMessage with single msg" should {

      for (statusCode, errMsg, statusCodeText) <- commonErrors ++ deleteMessageErrors do
        s"${errMsg}(${statusCode}) delete SQS message, single failed message" in new StreamMessageJourney {

          override def deleteMessage(): Unit = {
            sqsDeleteMessageError(statusCode, errMsg)
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)

          private val retryCount = if is5xx(statusCode) then 3 else 0
          verifySqsDeleteMessage(1 + retryCount)

          service.msgFailedCount shouldBe 1 withClue ("incorrect failedCount")
          service.msgSuccessCount shouldBe 0 withClue ("incorrect successCount")

          service.failedMsgMessages shouldBe List(s"Delete SQS message failed Service returned HTTP status code $statusCode (Service: Sqs, Status Code: $statusCode, Request ID: null) (SDK Attempt Count: ${1+retryCount})")
        }
    }

    "deleteMessage with recover on 2nd autoretry if statuscode > 500" should {
      for (statusCode, errMsg, statusCodeText) <- (commonErrors ++ deleteMessageErrors).filter( _._1 >= 500) do
        s"${errMsg}(${statusCode}) delete SQS message" in new StreamMessageJourney {
          override def deleteMessage(): Unit = {
            sqsDeleteMessageError(statusCode, errMsg, to="sqs-ok")
            sqsDeleteMessage(state="sqs-ok", to="sqs-ok")
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)

          private val retryCount: Int = if is5xx(statusCode) then 1 else 0
          verifySqsDeleteMessage(1 + retryCount)

          service.msgFailedCount shouldBe 0 withClue ("incorrect failedCount")
          service.msgSuccessCount shouldBe 1 withClue ("incorrect successCount")
        }
    }
  }

  "deleteBundle (S3)" should {
    "pass common error checks" should {
      for (statusCode, errMsg, statusCodeText) <- commonErrors do
        s"${errMsg}(${statusCode}) delete SQS message" in new StreamMessageJourney {

          override def deleteBundle(): Unit = {
            s3ErrorDeleteMessage(statusCode, errMsg, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)

          verifySqsDeleteMessage(1)
          private val retryCount = if is5xx(statusCode) then 3 else 0
          verifyDeleteMessage(1 + retryCount, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

          service.msgFailedCount shouldBe 1 withClue ("incorrect failedCount")
          service.msgSuccessCount shouldBe 0 withClue ("incorrect successCount")

          service.failedMsgMessages shouldBe List("""Delete Attachment Failed """)
        }
    }
  }

  def is5xx(status:Int): Boolean = status >= 500

  def waitForNMessages(n:Int, maxTime: Span = 30.seconds)(service:TestNonrepMicroservice): Unit = {
    eventually(timeout(maxTime), interval(1.second)) {
      service.msgCountTotal should be >= n
    }
  }

  def createNonrepMicroservice(testKit: ActorTestKit): TestNonrepMicroservice = {
    val service: TestNonrepMicroservice = TestNonrepMicroservice()(using testKit.internalSystem, mockConfig)
    service.addAttachmentsProcessorOnComplete()
    service
  }

}
