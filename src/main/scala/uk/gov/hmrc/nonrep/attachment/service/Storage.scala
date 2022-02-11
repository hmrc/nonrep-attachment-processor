package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.HttpEntity
import akka.stream.ActorAttributes
import akka.stream.Supervision.restartingDecider
import akka.stream.alpakka.s3.ObjectMetadata
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.util.ByteString
import software.amazon.awssdk.services.sqs.model.Message
import uk.gov.hmrc.nonrep.attachment.{AttachmentInfo, EitherErr}
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.{ExecutionContext, Future}

/**
 * It's an interim object before the final one is delivered
 */
trait Storage[A] {
  def downloadAttachment: Flow[A, EitherErr[A], NotUsed]

  def s3Source(attachment: A): Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed]
}

class StorageService()(implicit val config: ServiceConfig,
                       implicit val system: ActorSystem[Nothing]) extends Storage[AttachmentInfo] {

  implicit val ec: ExecutionContext = system.executionContext

  override def s3Source(attachment: AttachmentInfo):
  Source[Option[(Source[ByteString, NotUsed], ObjectMetadata)], NotUsed] =
    S3.download(config.attachmentsBucket, s"${attachment.key}.zip")

  override def downloadAttachment(): Flow[AttachmentInfo, EitherErr[AttachmentInfo], NotUsed] = {
    Flow[AttachmentInfo].mapAsyncUnordered(8) { attachment =>
      s3Source(attachment).toMat(Sink.head)(Keep.right).run().flatMap {
        case None => Future.successful(Left(ErrorMessage(s"Error getting attachment ${attachment.key} from S3 ${config.attachmentsBucket}")))
        case Some(source) => source._1.runFold(ByteString(ByteString.empty))(_ ++ _).map(bytes => Right(attachment.copy(content = Some(bytes))))
      }
    }.withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))
  }

}
