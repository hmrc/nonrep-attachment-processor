package uk.gov.hmrc.nonrep.attachment.service

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.stream.ActorAttributes
import org.apache.pekko.stream.Supervision.restartingDecider
import org.apache.pekko.stream.connectors.s3.{ObjectMetadata, S3Exception}
import org.apache.pekko.stream.connectors.s3.scaladsl.S3
import org.apache.pekko.stream.scaladsl.{Flow, Keep, Sink, Source}
import org.apache.pekko.util.ByteString
import org.apache.pekko.{Done, NotUsed}
import uk.gov.hmrc.nonrep.attachment.*
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.{ExecutionContext, Future}

trait Storage {

  def downloadAttachment: Flow[EitherErr[AttachmentInfoMessage], EitherErr[AttachmentContentMessage], NotUsed]

  def deleteAttachment: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed]
}

class StorageService()(using config: ServiceConfig, system: ActorSystem[?]) extends Storage {

  implicit val ec: ExecutionContext = system.executionContext

  protected def s3DownloadSource(attachment: AttachmentInfoMessage): Source[ByteString, Future[ObjectMetadata]] =
    S3.getObject(config.attachmentsBucket, attachment.s3ObjectKey)

  override def downloadAttachment: Flow[EitherErr[AttachmentInfoMessage], EitherErr[AttachmentContentMessage], NotUsed] =
    Flow[EitherErr[AttachmentInfoMessage]]
      .mapAsyncUnordered(8) {
        case Left(error)       => Future.successful(Left(error))
        case Right(attachment) =>
          val (metadata, stream) = s3DownloadSource(attachment).toMat(Sink.reduce[ByteString](_ ++ _))(Keep.both).run()
          metadata
            .flatMap(_ => stream.map(content => Right(AttachmentContentMessage(attachment, content)).withLeft[AttachmentError]))
            .recover { case e =>
              system.log.error(s"Error getting object ${attachment.s3ObjectKey} from s3 ${config.attachmentsBucket}", e)
              Left(
                ErrorMessageWithDeleteSQSMessage(
                  messageId = attachment.message,
                  message = s"failed to download ${attachment.s3ObjectKey} attachment bundle from s3 ${config.attachmentsBucket}",
                  optThrowable = Some(e),
                  severity = WARN
                )
              )
            }
      }
      .withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))

  protected def s3DeleteSource(attachment: AttachmentInfo): Source[Done, NotUsed] =
    S3.deleteObject(config.attachmentsBucket, attachment.s3ObjectKey)

  override def deleteAttachment: Flow[EitherErr[AttachmentInfo], EitherErr[AttachmentInfo], NotUsed] =
    Flow[EitherErr[AttachmentInfo]].mapAsyncUnordered(8) {
      case Left(error)       => Future.successful(Left(error))
      case Right(attachment) => s3DeleteSource(attachment).toMat(Sink.head)(Keep.right).run().map(_ => Right(attachment))
    }
}
