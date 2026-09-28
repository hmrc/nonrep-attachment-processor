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
    testKit.system.terminate()
  }

  val commonErrors: Seq[(Int, String)] = List(
    (400, "AccessDeniedException"),
    (400, "IncompleteSignature"),
    (500, "InternalFailure"),
    (400, "InvalidAction"),
    (403, "InvalidClientTokenId"),
    (400, "InvalidParameterCombination"),
    (400, "InvalidParameterValue"),
    (400, "InvalidQueryParameter"),
    (404, "MalformedQueryString"),
    (400, "MissingAction"),
    (400, "MissingAuthenticationToken"),
    (400, "MissingParameter"),
    (400, "NotAuthorized"),
    (403, "OptInRequired"),
    (400, "RequestExpired"),
    (503, "ServiceUnavailable"),
    (403, "ThrottlingException"),
    (400, "ValidationError")
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

  "basic checks (it works)" should {

    "work with single msg ok" in new StreamMessageJourney{
      createMessageJourney()

      private val service = createNonrepMicroservice(testKit)

      waitForNMessages(1)(service)

      service.msgSuccessCount shouldBe 1 withClue ("incorrect successCount")
      verifyDeleteMessage(1, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")

    }

    "work with two msg ok" in new StreamMessageJourney {
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
    "pass common error checks" should {
      for (statusCode, errMsg) <- commonErrors do
        s"${errMsg}(${statusCode}) SQS request error (error msg followed by ok msg)" in new StreamMessageJourney {
          override def getMessages(): Unit = {
            sendSQSErrorMessage(statusCode, errMsg, to = "msg-2")
            sendSQSMessage(state = "msg-2", to = "no-msg")
            noSQSMessage(state = "no-msg", to = "no-msg")
          }
          createMessageJourney()

          private val service = createNonrepMicroservice(testKit)

          private val expectedSucessCount = 1
          private val expectErrorCount = if is5xx(statusCode) then 0 else 1 // if 5xx then SQS performs an internal retry and get the next msg

          waitForNMessages(expectedSucessCount + expectErrorCount)(service)

          service.msgSuccessCount shouldBe expectedSucessCount withClue ("incorrect successCount")
          service.msgFailedCount shouldBe expectErrorCount withClue ("incorrect failedCount")
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
        val failedCount: Int = service.msgFailedCount
        failedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")
        verifySqsDeleteMessage(1)
      }

      "Fault check" should {
        for fault <- faultList do
          s"${fault.name} Failed SQS msg" in new StreamMessageJourney {
            override def getMessages(): Unit = {
              sendFaultSQSMessage(fault)
            }

            createMessageJourney()

            private val service = createNonrepMicroservice(testKit)

            waitForNMessages(1)(service)

            service.msgFailedCount shouldBe 1 withClue ("failedCount")
            service.msgSuccessCount shouldBe 0 withClue ("successCount")
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
    }

    "s3 common errors" should {
      for ((statusCode, errMsg) <- commonErrors) {
        s"${errMsg}(${statusCode}) S3 request error" in new StreamMessageJourney {

          override def downloadBundle(): Unit = {
            failedErrorGetAttachment("d9b3f2f3-32e1-4903-b812-a64c2a045c61", statusCode, errMsg)
          }

          createMessageJourney()
          private val service = createNonrepMicroservice(testKit)
          waitForNMessages(1)(service)
          service.msgFailedCount shouldBe 1 withClue (s"incorrect failedCount  Success:${service.msgSuccessCount}")

          verifyDeleteMessage(0, "local-nonrep-attachment-data", "d9b3f2f3-32e1-4903-b812-a64c2a045c61.zip")
        }
      }
    }
  }

  "signAttachment" should {
    "pass common error checks" should {
      for (statusCode, errMsg) <- commonErrors do
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
    }


    "pass common error checks" should {
      for (statusCode, errMsg) <- commonErrors do
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
    }

  }

  "updateMetastore" should {
    "pass common error checks" should {
      for (statusCode, errMsg) <- commonErrors do
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
        }
    }
  }

  // https://docs.aws.amazon.com/AWSSimpleQueueService/latest/APIReference/API_DeleteMessage.html
  val deleteMessageErrors: Seq[(Int, String)] = List(
    (400, "InvalidAddress"),
    (400, "InvalidIdFormat"),
    (400, "InvalidSecurity"),
    (400, "QueueDoesNotExist"),
    (400, "ReceiptHandleIsInvalid"),
    (400, "RequestThrottled"),
    (400, "UnsupportedOperation")
  )

  "deleteMessage (SQS)" should {
    "deleteMessage with single msg" should {

      for (statusCode, errMsg) <- commonErrors ++ deleteMessageErrors do
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

        }
    }

    "deleteMessage with recover on 2nd autoretry if statuscode > 500" should {
      for (statusCode, errMsg) <- (commonErrors ++ deleteMessageErrors).filter( _._1 >= 500) do
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
      for (statusCode, errMsg) <- commonErrors do
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
