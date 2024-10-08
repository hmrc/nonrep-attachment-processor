package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.Supervision.restartingDecider
import org.apache.pekko.stream.connectors.s3.ObjectMetadata
import org.apache.pekko.stream.connectors.s3.scaladsl.S3
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed}
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.{ExecutionContext, Future}

trait Storage {

  def downloadAttachment: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed]

  def deleteAttachment: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]

}

class StorageService()(implicit val config: ServiceConfig,
                       implicit val system: ActorSystem[_]) extends Storage {

  implicit val ec: ExecutionContext = system.executionContext

  protected def s3DownloadSource(attachment: AttachmentInfo):
  Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
    S3.download(config.attachmentsBucket, attachment.s3ObjectKey)

  override def downloadAttachment: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentContent], NotUsed] = {
    Flow[EitherErr[AttachmentInfo]].mapAsyncUnordered(8) {
      case Left(error) => Future.successful(Left(error))
      case Right(attachment) => s3DownloadSource(attachment).toMat(Sink.head)(Keep.right).run().flatMap {
        case None         =>
          Future.successful(Left(ErrorMessageWithDeleteSQSMessage(
            messageId     = attachment.message,
            message       = s"failed to download ${attachment.s3ObjectKey} attachment bundle from s3 ${config.attachmentsBucket}",
            optThrowable  = None,
            severity      = WARN
          )))
        case Some(source) => source._1.runFold(ByteString(ByteString.empty))(_ ++ _).map(bytes => Right(AttachmentContent(attachment, bytes)))
      }
    }.withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))
  }

  protected def s3DeleteSource(attachment: AttachmentInfo): Source[Done, NotUsed] =
    S3.deleteObject(config.attachmentsBucket, attachment.s3ObjectKey)

  override def deleteAttachment: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
    Flow[EitherErr[AttachmentInfo]].mapAsyncUnordered(8) {
      case Left(error) => Future.successful(Left(error))
      case Right(attachment) => s3DeleteSource(attachment).toMat(Sink.head)(Keep.right).run().map(_ => Right(attachment))
    }
}
