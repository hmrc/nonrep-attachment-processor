package uk.gov.hmrc.nonrep.attachment

import java.io.File
import java.nio.file.Files

import akka.NotUsed
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.ResponseEntity
import akka.stream.alpakka.s3.ObjectMetadata
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.service.{Storage, StorageService}

import scala.concurrent.ExecutionContext

object TestServices {

  lazy val testKit = ActorTestKit()

  implicit val typedSystem: ActorSystem[_] = testKit.internalSystem

  implicit val config: ServiceConfig = new ServiceConfig()

  def entityToString(entity: ResponseEntity)(implicit ec: ExecutionContext) =
    entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

  val sampleAttachment: Array[Byte] =
    Files.readAllBytes(new File(getClass.getClassLoader.getResource("738bcba6-7f9e-11ec-8768-3f8498104f38.zip").getFile).toPath)

  object success {
    implicit val storage: Storage[AttachmentInfo] = new StorageService() {
      override def s3Source(attachment: AttachmentInfo): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
        Source.single(Some(Source.single(ByteString(sampleAttachment)), ObjectMetadata(Seq())))
    }

  }

  object failure {
    implicit val storage: Storage[AttachmentInfo] = new StorageService() {
      override def s3Source(attachment: AttachmentInfo): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
        Source.single(None)
    }

  }
}