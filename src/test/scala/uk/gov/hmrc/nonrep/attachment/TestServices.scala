package uk.gov.hmrc.nonrep.attachment

import java.io.File
import java.nio.file.Files
import java.util.UUID

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes.{InternalServerError, OK}
import akka.http.scaladsl.model.{HttpEntity, HttpRequest, HttpResponse, ResponseEntity}
import akka.stream.alpakka.s3.ObjectMetadata
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.testkit.scaladsl.TestSink
import akka.util.ByteString
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.service._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

object TestServices {

  lazy val testKit = ActorTestKit()

  implicit val typedSystem: ActorSystem[_] = testKit.internalSystem
  implicit val ec: ExecutionContext = typedSystem.executionContext
  implicit val config: ServiceConfig = new ServiceConfig()

  def entityToString(entity: ResponseEntity)(implicit ec: ExecutionContext): Future[String] =
    entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

  val testAttachmentId = "738bcba6-7f9e-11ec-8768-3f8498104f38"
  val sampleAttachmentMetadata: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(METADATA_FILE).getFile).toPath)
  val sampleAttachment: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(s"$testAttachmentId.zip").getFile).toPath)
  val sampleAttachmentContent: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(testAttachmentId).getFile).toPath)
  val sampleSignedAttachmentContent: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource(s"$testAttachmentId.p7m").getFile).toPath)

  val testApplicationSink = TestSink.probe[EitherErr[AttachmentContent]](typedSystem.classicSystem)

  object success {
    val storageService: Storage = new StorageService() {
      override def s3Source(attachment: AttachmentInfo): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
        Source.single(Some(Source.single(ByteString(sampleAttachment)), ObjectMetadata(Seq())))
    }
    /*
    For queue service - this may be considered an interim solution
     */
    val queueService: Queue = new Queue() {
      override def getMessages: Source[Message, NotUsed] = Source.single(Message.builder().messageId(UUID.randomUUID().toString).build())

      override def parseMessages: Flow[Message, AttachmentInfo, NotUsed] = Flow[Message].map {
        message => AttachmentInfo(message.messageId(), testAttachmentId)
      }

      override def deleteMessage: Flow[AttachmentInfo, Boolean, NotUsed] = Flow[AttachmentInfo].map { _ => true }
    }

    val signService: Sign = new SignService() {
      override val callDigitalSignatures: Flow[(HttpRequest, EitherErr[ZipContent]), (Try[HttpResponse], EitherErr[ZipContent]), Any] =
        Flow[(HttpRequest, EitherErr[ZipContent])].map {
          case (_, request) => (Try(HttpResponse(OK, entity = HttpEntity(sampleSignedAttachmentContent))), request)
        }
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

  }

}