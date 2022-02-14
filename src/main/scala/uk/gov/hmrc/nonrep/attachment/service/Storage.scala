package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.ActorAttributes
import akka.stream.Supervision.stoppingDecider
import akka.stream.alpakka.s3.ObjectMetadata
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.{ExecutionContext, Future}

trait Storage {

  def downloadAttachment(): Flow[AttachmentInfo, EitherErr[AttachmentContent], NotUsed]

}

class StorageService()(implicit val config: ServiceConfig,
                       implicit val system: ActorSystem[_]) extends Storage {

  implicit val ec: ExecutionContext = system.executionContext

  protected def s3Source(attachment: AttachmentInfo):
  Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
    S3.download(config.attachmentsBucket, s"${attachment.key}.zip")

  override def downloadAttachment(): Flow[AttachmentInfo, EitherErr[AttachmentContent], NotUsed] = {
    Flow[AttachmentInfo].mapAsyncUnordered(8) { attachment =>
      s3Source(attachment).toMat(Sink.head)(Keep.right).run().flatMap {
        case None => Future.successful(Left(ErrorMessage(s"Error getting attachment ${attachment.key} from S3 ${config.attachmentsBucket}", WARN)))
        case Some(source) => source._1.runFold(ByteString(ByteString.empty))(_ ++ _).map(bytes => Right(AttachmentContent(attachment, bytes)))
      }
    }.withAttributes(ActorAttributes.supervisionStrategy(stoppingDecider))
    //TODO: determine whether restartingDecider or stoppingDecider is more appropriate for keeping stream alive
  }

}
