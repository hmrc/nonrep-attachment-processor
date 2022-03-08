package uk.gov.hmrc.nonrep.attachment

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes.{InternalServerError, OK}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, ResponseEntity}
import akka.stream.alpakka.s3.ObjectMetadata
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.services.glacier.model.{UploadArchiveRequest, UploadArchiveResponse}
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.service._

import java.io.File
import java.nio.file.Files
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object TestServices {

  lazy val testKit: ActorTestKit = ActorTestKit()

  implicit val typedSystem: ActorSystem[_] = testKit.internalSystem
  implicit val ec: ExecutionContext = typedSystem.executionContext
  implicit val config: ServiceConfig = new ServiceConfig()

  def entityToString(entity: ResponseEntity)(implicit ec: ExecutionContext): Future[String] =
    entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

  val testAttachmentId = "738bcba6-7f9e-11ec-8768-3f8498104f38"
  val archiveId = "archiveId"
  val sampleAttachmentMetadata: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(METADATA_FILE).getFile).toPath)
  val sampleAttachment: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(s"$testAttachmentId.zip").getFile).toPath)
  val sampleAttachmentContent: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(testAttachmentId).getFile).toPath)
  val sampleSignedAttachmentContent: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(s"$testAttachmentId.p7m").getFile).toPath)

  val testApplicationSink: Sink[EitherErr[ArchivedAttachment], TestSubscriber.Probe[EitherErr[ArchivedAttachment]]] =
    TestSink.probe[EitherErr[ArchivedAttachment]](typedSystem.classicSystem)

  def testSQSMessage(env: String, messageId: String, attachmentId: String) = Message.builder().messageId(messageId).body(
    s"""
    {
       "Records":[
          {
             "eventVersion":"2.1",
             "eventSource":"aws:s3",
             "awsRegion":"eu-west-2",
             "eventTime":"2022-02-07T15:11:29.424Z",
             "eventName":"ObjectCreated:Put",
             "userIdentity":{
                "principalId":"AWS:AROAS3WPTASL6ZOV32XXX:xxx.xxx"
             },
             "requestParameters":{
                "sourceIPAddress":"11.132.226.116"
             },
             "responseElements":{
                "x-amz-request-id":"QRD2XCV9XFBZ6WJD4",
                "x-amz-id-2":"wGP2l4wq5ZhzxzcxCNcK9jik8VmXMO+c8HBYv66BJiWGD8vLAWaKyusb9Ifzo0lbK92CgohDuetpRcPQTldmUsSZnC44mqHfgwyL9e7WFwnKh7ug="
             },
             "s3":{
                "s3SchemaVersion":"1.0",
                "configurationId":"tf-s3-queue-20220207093133700300000025",
                "bucket":{
                   "name":"$env-nonrep-attachment-data",
                   "ownerIdentity":{
                      "principalId":"A202PFQUTJVXI"
                   },
                   "arn":"arn:aws:s3:::$env-nonrep-attachment-data"
                },
                "object":{
                   "key":"$attachmentId.zip",
                   "size":2063,
                   "eTag":"28e4175ade4bacd44d180b90d719e921",
                   "sequencer":"00620136A15E3CA4A7"
                }
             }
          }
       ]
    }
    """).build()

  object success {
    val storageService: Storage = new StorageService() {
      override def s3Source(attachment: AttachmentInfo): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
        Source.single(Some(Source.single(ByteString(sampleAttachment)), ObjectMetadata(Seq())))
    }
    /*
    For queue service - this may be considered an interim solution
     */
    val queueService: Queue = new QueueService() {
      override def getMessages: Source[Message, NotUsed] =
        Source(IndexedSeq.fill(3)(testSQSMessage(config.env, UUID.randomUUID().toString, testAttachmentId)))

      override def deleteMessage(): Flow[AttachmentInfo, Boolean, NotUsed] = Flow[AttachmentInfo].map { _ => true }
    }

    val signService: Sign = new SignService() {
      override val callDigitalSignatures: Flow[(HttpRequest, EitherErr[ZipContent]), (Try[HttpResponse], EitherErr[ZipContent]), Any] =
        Flow[(HttpRequest, EitherErr[ZipContent])].map {
          case (_, request) => (Try(HttpResponse(OK, entity = HttpEntity(sampleSignedAttachmentContent))), request)
        }
    }

    val glacierService: Glacier = new GlacierService("", "") {
      override def eventuallyArchive(uploadArchiveRequest: UploadArchiveRequest,
                                     asyncRequestBody: AsyncRequestBody): Future[UploadArchiveResponse] =
        Future successful UploadArchiveResponse.builder().archiveId(archiveId).build()
    }
  }

  object failure {
    val storageService: Storage = new StorageService() {
      override def s3Source(attachment: AttachmentInfo): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
        Source.single(None)
    }
    val signService: Sign = new SignService() {
      override val callDigitalSignatures: Flow[(HttpRequest, EitherErr[ZipContent]), (Try[HttpResponse], EitherErr[ZipContent]), Any] =
        Flow[(HttpRequest, EitherErr[ZipContent])].map {
          case (_, request) => (Try(HttpResponse(InternalServerError)), request)
        }
    }
    val glacierService: GlacierService = new GlacierService("", "") {
      override def eventuallyArchive(uploadArchiveRequest: UploadArchiveRequest,
                                     asyncRequestBody: AsyncRequestBody): Future[UploadArchiveResponse] =
        Future failed new RuntimeException("boom!")
    }
  }

}