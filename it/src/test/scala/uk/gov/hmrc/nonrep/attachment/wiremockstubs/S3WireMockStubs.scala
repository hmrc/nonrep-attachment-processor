package uk.gov.hmrc.nonrep.attachment.wiremockstubs

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, delete, deleteRequestedFor, equalTo, get, getRequestedFor, post, postRequestedFor, put, putRequestedFor, urlEqualTo, urlMatching, urlPathEqualTo}
import com.github.tomakehurst.wiremock.stubbing.{Scenario, StubMapping}
import org.scalatest.time.Span

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files

trait S3WireMockStubs {
  this: WireMockSupport =>

  /*
   these requests are used to enable testing and verify requests.

   all known s3 errors can be found here https://docs.aws.amazon.com/AmazonS3/latest/API/ErrorResponses.html#RESTErrorResponses
   and https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html
   */
  def successfulAttachmentSizeCheck(attachmentId: String, bytes: String): StubMapping =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/s3/attachments/public/$attachmentId"))
        .withHeader("Range", equalTo("bytes=0-0"))
        .willReturn(
          aResponse()
            .withStatus(206)
            .withHeader("Content-Range", s"bytes 0-10/$bytes"))
    )

  def failedAttachmentSizeCheck(attachmentId: String, bytes: String): StubMapping =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/s3/attachments/public/$attachmentId"))
        .withHeader("Range", equalTo("bytes=0-0"))
        .willReturn(
          aResponse()
            .withStatus(500)
        )
    )

  def verifyAttachmentSizeCheck(attachmentId: String, times: Int): Unit =
    wireMockServer
      .verify(times, getRequestedFor(urlEqualTo(s"/s3/attachments/public/$attachmentId")).withHeader("Range", equalTo("bytes=0-0")))

  def successfulAttachmentDownload(attachmentId: String): StubMapping =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/s3/attachments/public/$attachmentId"))
        .withHeader("Range", WireMock.not(equalTo("bytes=0-0")))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody("test_file".getBytes(Charset.forName("UTF-8")))
        )
    )

  def failedAttachmentDownload(attachmentId: String): StubMapping =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/s3/attachments/public/$attachmentId"))
        .withHeader("Range", WireMock.not(equalTo("bytes=0-0")))
        .willReturn(
          aResponse()
            .withStatus(500)
            .withBody("invalid".getBytes(Charset.forName("UTF-8")))
        )
    )

  def verifyAttachmentDownload(attachmentId: String, times: Int): Unit =
    wireMockServer.verify(
      times,
      getRequestedFor(urlEqualTo(s"/s3/attachments/public/$attachmentId")).withHeader("Range", WireMock.not(equalTo("bytes=0-0"))))

  def successfulInitiateMultipartUpload(attachmentId: String): StubMapping =
    wireMockServer.stubFor(
      post(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .withQueryParam("uploads", equalTo(""))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(s"""            <InitiateMultipartUploadResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                         |              <Bucket>local-nonrep-attachment-data</Bucket>
                         |              <Key>$attachmentId.zip</Key>
                         |              <UploadId>VXBsb2FkIElEIGZvciA2aWWpbmcncyBteS1tb3ZpZS5tMnRzIHVwbG9hZA</UploadId>
                         |            </InitiateMultipartUploadResult>""".stripMargin.getBytes(Charset.forName("UTF-8")))
        )
    )

  def failedInitiateMultipartUpload(attachmentId: String): StubMapping =
    wireMockServer.stubFor(
      post(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .withQueryParam("uploads", equalTo(""))
        .willReturn(
          aResponse()
            .withStatus(500)
            .withBody(s"""invalid""".stripMargin.getBytes(Charset.forName("UTF-8")))
        )
    )

  def verifyInitiateMultipartUpload(attachmentId: String, times: Int): Unit =
    wireMockServer.verify(
      times,
      postRequestedFor(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip")).withQueryParam("uploads", equalTo("")))

  def successfulUploadPart(attachmentId: String, partNumber: Int): StubMapping =
    wireMockServer.stubFor(
      put(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .withQueryParam("partNumber", equalTo(partNumber.toString))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("ETag", s"etag-part-$partNumber")
        )
    )

  def failedUploadPart(attachmentId: String, partNumber: Int): StubMapping =
    wireMockServer.stubFor(
      put(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .withQueryParam("partNumber", equalTo(partNumber.toString))
        .willReturn(
          aResponse()
            .withStatus(500)
            .withHeader("ETag", s"etag-part-$partNumber")
        )
    )

  def verifyUploadPart(attachmentId: String, partNumber: Int, times: Int): Unit =
    wireMockServer.verify(
      times,
      putRequestedFor(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .withQueryParam("partNumber", equalTo(partNumber.toString)))

  def successfulCompleteMultipartUpload(attachmentId: String): StubMapping =
    wireMockServer.stubFor(
      post(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .willReturn(
          aResponse()
            .withHeader("content-type", "application/xml; charset=UTF-8")
            //                .withHeader("ETag", "7e10e7d25dc4581d89b9285be5f384fd")
            .withStatus(200)
            .withBody(s"""<CompleteMultipartUploadResult>
                         |   <Location>string</Location>
                         |   <Bucket>local-nonrep-attachment-data</Bucket>
                         |   <Key>$attachmentId.zip</Key>
                         |   <ETag>b54357faf0632cce46e942fa68356b38</ETag>
                         |   <ChecksumCRC32>string</ChecksumCRC32>
                         |   <ChecksumCRC32C>string</ChecksumCRC32C>
                         |   <ChecksumCRC64NVME>string</ChecksumCRC64NVME>
                         |   <ChecksumSHA1>string</ChecksumSHA1>
                         |   <ChecksumSHA256>string</ChecksumSHA256>
                         |   <ChecksumType>string</ChecksumType>
                         |</CompleteMultipartUploadResult>""".stripMargin.getBytes(Charset.forName("UTF-8")))
        )
    )

  def failedCompleteMultipartUpload(attachmentId: String): StubMapping =
    wireMockServer.stubFor(
      post(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .withQueryParam("uploads", WireMock.not(equalTo("")))
        .willReturn(
          aResponse()
            .withHeader("content-type", "application/xml; charset=UTF-8")
            .withStatus(500)
            .withBody(s"""invalid""".stripMargin.getBytes(Charset.forName("UTF-8")))
        )
    )

  def verifyCompleteMultipartUpload(attachmentId: String, times: Int): Unit =
    wireMockServer.verify(
      times,
      postRequestedFor(urlPathEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .withQueryParam("uploads", WireMock.not(equalTo(""))))

  /*
    these requests are used to facilitate ping and test-only
   */
  def successfulPing(): StubMapping =
    wireMockServer.stubFor(
      put(urlMatching("/local-nonrep-attachment-data/.*/ping.txt"))
        .willReturn(
          aResponse()
            .withStatus(200)
        )
    )

  def failedPing(): StubMapping =
    wireMockServer.stubFor(
      put(urlMatching("/local-nonrep-attachment-data/.*/ping.txt"))
        .willReturn(
          aResponse()
            .withStatus(500)
        )
    )

  def verifyPing(times: Int): Unit =
    wireMockServer.verify(times, putRequestedFor(urlMatching("/local-nonrep-attachment-data/.*/ping.txt")))

  def successfulGetAttachment(attachmentId: String): StubMapping =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withBody(
              Files.readAllBytes(new File(getClass.getClassLoader.getResource("attachments/0f0d6508-7f9f-11ec-b1fb-a732847931b5.zip").getFile).toPath))
        )
    )
  
  // https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html#API_GetObject_Errors
  def failedGetAttachment(attachmentId: String): StubMapping =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .willReturn(
          aResponse()
            .withStatus(404)
            .withBody(s"""<Error>
                         |  <Code>NoSuchKey</Code>
                         |  <Message>The resource you requested does not exist</Message>
                         |  <Resource>/mybucket/myfoto.jpg</Resource>
                         |  <RequestId>4442587FB7D0A2F9</RequestId>
                         |</Error>""".stripMargin.getBytes(Charset.forName("UTF-8")))
        )
    )

  def failedGetAttachmentTimeout(attachmentId: String, delay:Span): StubMapping =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .willReturn(
          aResponse()
            .withFixedDelay(delay.millisPart.toInt)
            .withStatus(404)
            .withBody(s"""<Error>
                         |  <Code>NoSuchKey</Code>
                         |  <Message>The resource you requested does not exist</Message>
                         |  <Resource>/mybucket/myfoto.jpg</Resource>
                         |  <RequestId>4442587FB7D0A2F9</RequestId>
                         |</Error>""".stripMargin.getBytes(Charset.forName("UTF-8")))
        )
    )

  def failedErrorGetAttachment(attachmentId: String, statusCode:Int, error:String): StubMapping =
    wireMockServer.stubFor(
      get(urlEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip"))
        .willReturn(
          aResponse()
            .withStatus(statusCode)
            .withBody(s"""<Error>
                         |  <Code>$error</Code>
                         |  <Message>The resource you requested does not exist</Message>
                         |  <Resource>/mybucket/myfoto.jpg</Resource>
                         |  <RequestId>4442587FB7D0A2F9</RequestId>
                         |</Error>""".stripMargin.getBytes(Charset.forName("UTF-8")))
        )
    )

  def verifyGetAttachment(attachmentId: String, times: Int): Unit =
    wireMockServer.verify(times, getRequestedFor(urlEqualTo(s"/local-nonrep-attachment-data/$attachmentId.zip")))

  def successfulListBucket(): StubMapping =
    wireMockServer.stubFor(
      get(urlPathEqualTo(s"/local-nonrep-attachment-data"))
        .willReturn(
          aResponse()
            .withStatus(200)
            .withHeader("content-type", "application/xml; charset=UTF-8")
            .withBody(s"""<ListBucketResult xmlns="http\\://s3.amazonaws.com/doc/2006-03-01/">
                         |  <Name>local-nonrep-attachment-data</Name>
                         |  <Prefix></Prefix>
                         |  <KeyCount>2</KeyCount>
                         |  <MaxKeys>1000</MaxKeys>
                         |  <IsTruncated>false</IsTruncated>
                         |  <Contents>
                         |    <Key>0f0d6508-7f9f-11ec-b1fb-a732847931b5.zip</Key>
                         |    <LastModified>2024-01-01T00:00:00.000Z</LastModified>
                         |    <ETag>"9d963647c09ce5b31d55bb6b2f0c887b280320e7a92e064fbea1c1326c0f82ac"</ETag>
                         |    <Size>12345</Size>
                         |    <StorageClass>STANDARD</StorageClass>
                         |  </Contents>
                         |</ListBucketResult>""".stripMargin.getBytes(Charset.forName("UTF-8")))
        )
    )

  def failedListBucket(error: Int): StubMapping =
    wireMockServer.stubFor(
      get(urlPathEqualTo(s"/local-nonrep-attachment-data"))
        .willReturn(
          aResponse()
            .withStatus(error)
        )
    )

  def verifyListBucket(times: Int): Unit =
    wireMockServer.verify(times, getRequestedFor(urlPathEqualTo(s"/local-nonrep-attachment-data")))
  
  def s3DeleteMessage( bucket:String, objKey:String, state: String = Scenario.STARTED, to:String = Scenario.STARTED) =
    wireMockServer.stubFor(
      delete(urlPathEqualTo(s"/$bucket/$objKey"))
        .inScenario(state)
        .whenScenarioStateIs(state)
        .willReturn(
          aResponse()
            .withStatus(204)
            .withBody("")
        )
        .willSetStateTo(to)
    )

  def s3ErrorDeleteMessage(statusCode:Int, errMsg:String,  bucket:String, objKey:String, state: String = Scenario.STARTED, to:String = Scenario.STARTED) =
    wireMockServer.stubFor(
      delete(urlPathEqualTo(s"/$bucket/$objKey"))
        .inScenario(state)
        .whenScenarioStateIs(state)
        .willReturn(
          aResponse()
            .withStatus(statusCode)
            .withBody("")
        )
        .willSetStateTo(to)
    )

  def verifyDeleteMessage(times: Int = 1, bucket:String, objKey:String): Unit =
    wireMockServer.verify(times, deleteRequestedFor(urlPathEqualTo(s"/$bucket/$objKey")))

}
