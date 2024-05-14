package uk.gov.hmrc.nonrep.attachment

import java.io.File
import java.nio.file.Files
import java.time.temporal.{ChronoUnit, TemporalUnit}
import java.util.UUID

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes.{InternalServerError, OK}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, ResponseEntity}
import akka.stream.alpakka.s3.ObjectMetadata
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import akka.{Done, NotUsed}
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.glacier.model.{UploadArchiveRequest, UploadArchiveResponse}
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.service._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object TestServices {

  lazy val testKit: ActorTestKit = ActorTestKit()

  implicit val typedSystem: ActorSystem[_] = testKit.internalSystem
  implicit val ec: ExecutionContext = typedSystem.executionContext
  implicit val config: ServiceConfig = new ServiceConfig()

  def entityToString(entity: ResponseEntity): Future[String] =
    entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

  val testAttachmentId = "738bcba6-7f9e-11ec-8768-3f8498104f38"
  val archiveId = "archiveId"
  val sampleAttachmentMetadata: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(METADATA_FILE).getFile).toPath)
  val sampleAttachment: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(s"$testAttachmentId.zip").getFile).toPath)
  val sampleErrorAttachmentMissingMetadata: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(s"${testAttachmentId}_missing_metadata.zip").getFile).toPath)
  val sampleAttachmentContent: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(testAttachmentId).getFile).toPath)
  val sampleSignedAttachmentContent: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(s"$testAttachmentId.p7m").getFile).toPath)

  val testApplicationSink: Sink[EitherErr[AttachmentInfo], TestSubscriber.Probe[EitherErr[AttachmentInfo]]] =
    TestSink.probe[EitherErr[AttachmentInfo]](typedSystem.classicSystem)

  val testSQSMessageIds: IndexedSeq[String] = IndexedSeq.fill(3)(UUID.randomUUID().toString)

  def testSQSMessage(env: String, messageId: String, attachmentId: String, service: String = "s3"): Message = Message.builder().receiptHandle(messageId).body(
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
             "$service":{
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
      override def s3DownloadSource(attachment: AttachmentInfo): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
        Source.single(Some(Source.single(ByteString(sampleAttachment)), ObjectMetadata(Seq())))

      override def s3DeleteSource(attachment: AttachmentInfo): Source[Done, NotUsed] =
        Source.single(Done)
    }

    val queueService: Queue = new QueueService() {
      override def getMessages: Source[Message, NotUsed] =
        Source(testSQSMessageIds.map(id => testSQSMessage(config.env, id, testAttachmentId)))

      override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
        Flow[EitherErr[AttachmentInfo]].map {
          _.map(_ => AttachmentInfo(testSQSMessageIds.head, testAttachmentId))
        }
    }

    val signService: Sign = new SignService() {
      override val callDigitalSignatures: Flow[(HttpRequest, EitherErr[ZipContent]), (Try[HttpResponse], EitherErr[ZipContent]), Any] =
        Flow[(HttpRequest, EitherErr[ZipContent])].map {
          case (_, request) => (Try(HttpResponse(OK, entity = HttpEntity(sampleSignedAttachmentContent))), request)
        }
    }

    val glacierService: Glacier = new GlacierService() {
      override def eventuallyUploadArchive(uploadArchiveRequest: UploadArchiveRequest,
                                           asyncRequestBody: AsyncRequestBody): Future[UploadArchiveResponse] =
        Future successful UploadArchiveResponse.builder().archiveId(archiveId).build()
    }

    val updateService: Update = new UpdateService() {
      override val callMetastore: Flow[(HttpRequest, EitherErr[ArchivedAttachment]), (Try[HttpResponse], EitherErr[ArchivedAttachment]), Any] =
        Flow[(HttpRequest, EitherErr[ArchivedAttachment])].map {
          case (_, request) => (Try(HttpResponse(OK, entity = HttpEntity(""))), request)
        }

      override def createRequestsSignerParams: RequestsSignerParams = new RequestsSignerParams() {

        import RequestsSigner._

        override val amountToAdd: Long = 999
        override val unit: TemporalUnit = ChronoUnit.MILLIS

        override def params: Aws4SignerParams = Aws4SignerParams.builder()
          .awsCredentials(StaticCredentialsProvider.create(AwsBasicCredentials.create(UUID.randomUUID().toString, "xxx")).resolveCredentials()).ensure
          .signingRegion(Region.EU_WEST_2).ensure
          .signingName("es").ensure
          .build()
      }
    }

    val zipperService = new BundleService
  }

  object failure {
    val storageService: Storage = new StorageService() {
      override def s3DownloadSource(attachment: AttachmentInfo): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
        Source.single(None)

      override def deleteAttachment: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
        Flow[EitherErr[AttachmentInfo]].map { _ => Left(ErrorMessage("failure")) }
    }

    val signService: Sign = new SignService() {
      override val callDigitalSignatures: Flow[(HttpRequest, EitherErr[ZipContent]), (Try[HttpResponse], EitherErr[ZipContent]), Any] =
        Flow[(HttpRequest, EitherErr[ZipContent])].map {
          case (_, request) => (Try(HttpResponse(InternalServerError)), request)
        }
    }

    val glacierService: GlacierService = new GlacierService() {
      override def eventuallyUploadArchive(uploadArchiveRequest: UploadArchiveRequest,
                                           asyncRequestBody: AsyncRequestBody): Future[UploadArchiveResponse] =
        Future failed new RuntimeException("boom!")
    }

    val updateService: Update = new UpdateService() {
      override val updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] =
        Flow[EitherErr[ArchivedAttachment]].map { _ =>
          Left(ErrorMessage("failure")).withRight[AttachmentInfo]
        }
    }

    val queueService: Queue = new QueueService() {

      override def getMessages: Source[Message, NotUsed] =
        Source(testSQSMessageIds.map(id => testSQSMessage(config.env, id, testAttachmentId, "invalid")))

      override def deleteMessage: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
        Flow[EitherErr[AttachmentInfo]].mapAsyncUnordered(8) { info =>
          Future.successful(
            info.fold(
              err => Left(err),
              _ => Left(ErrorMessage("Delete SQS message failure")).withRight[AttachmentInfo])
          )
        }
    }
  }
}
