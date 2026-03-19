package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Supervision.restartingDecider
import org.apache.pekko.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, Partition, ZipWith}
import org.apache.pekko.stream.{ActorAttributes, FlowShape, OverflowStrategy}
import org.apache.pekko.util.ByteString
import uk.gov.hmrc.nonrep.attachment.*
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait Sign {
  val TransactionIdHeader = "x-transaction-id"

  def signing: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]
}

class SignService()(using config: ServiceConfig, system: ActorSystem[?]) extends Sign {

  private[service] def partitionRequests[A]() =
    Partition[EitherErr[A]](
      2,
      {
        case Left(_)  => 0
        case Right(_) => 1
      }
    )

  private[service] def parse(zip: EitherErr[ZipContent], response: HttpResponse): Future[EitherErr[AttachmentBinary]] = {
    import system.executionContext
    if response.status == OK then
      response.entity.dataBytes
        .runFold(ByteString.empty)(_ ++ _)
        .map(content => Right(content.toArray[Byte]))
    else {
      response.discardEntityBytes()
      val error = s"Response status ${response.status} from signatures service ${config.signaturesServiceHost}"
      Future.successful(Left(ErrorMessage(error)))
    }
  }

  val callDigitalSignatures: Flow[(HttpRequest, EitherErr[ZipContent]), (Try[HttpResponse], EitherErr[ZipContent]), Any] =
    (if config.isSignaturesServiceSecure then
       Http().cachedHostConnectionPoolHttps[EitherErr[ZipContent]](config.signaturesServiceHost, config.signaturesServicePort)
     else Http().cachedHostConnectionPool[EitherErr[ZipContent]](config.signaturesServiceHost, config.signaturesServicePort))
      .buffer(config.signServiceBufferSize, OverflowStrategy.backpressure)
      .async

  private[service] val signAttachmentRequest: Flow[EitherErr[ZipContent], (HttpRequest, EitherErr[ZipContent]), NotUsed] =
    Flow[EitherErr[ZipContent]].map(zip =>
      zip.fold(
        error => HttpRequest() -> Left(error), // Have to keep http request outside either due to contract of HttpMethod
        { content =>
          val headers = List(RawHeader(TransactionIdHeader, content.info.attachmentId))
          val request = HttpRequest(
            HttpMethods.POST,
            s"/${config.signaturesServiceHost}/cades/${config.signingProfile}",
            headers,
            HttpEntity(content.attachment)
          )
          (request, zip)
        }
      )
    )

  private[service] val signAttachmentMetadataRequest: Flow[EitherErr[ZipContent], (HttpRequest, EitherErr[ZipContent]), NotUsed] =
    Flow[EitherErr[ZipContent]].map(zip =>
      zip.fold(
        error => HttpRequest() -> Left(error), // Have to keep http request outside either due to contract of HttpMethod
        { content =>
          val headers = List(RawHeader(TransactionIdHeader, content.info.attachmentId))
          val request = HttpRequest(
            HttpMethods.POST,
            s"/${config.signaturesServiceHost}/cades/${config.signingProfile}",
            headers,
            HttpEntity(content.metadata)
          )
          (request, zip)
        }
      )
    )

  val parseResponse: Flow[(Try[HttpResponse], EitherErr[ZipContent]), EitherErr[AttachmentBinary], NotUsed] =
    Flow[(Try[HttpResponse], EitherErr[ZipContent])]
      .mapAsyncUnordered(8) { case (httpResponse, request) =>
        httpResponse match {
          case Success(response)  => parse(request, response)
          case Failure(exception) =>
            Future.successful(
              Left(ErrorMessage(s"Failure connection to ${config.signaturesServiceHost} with ${exception.getMessage}", Some(exception)))
            )
        }
      }
      .withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))

  private[service] val remapErrorSeverity: Flow[EitherErr[AttachmentBinary], EitherErr[AttachmentBinary], NotUsed] =
    Flow[EitherErr[AttachmentBinary]].map {
      _.left.map(error => ErrorMessage(error.message, None, WARN))
    }

  private[service] val errorTransform: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed] =
    Flow[EitherErr[ZipContent]]
      .map(
        _.fold[EitherErr[SignedZipContent]](
          error => Left(error),
          content =>
            Left(ErrorMessage(s"partition failed for ${content.info.attachmentId}, this attachment should be getting signed", None, ERROR))
        )
      )

  private[service] def zip3[A, B, C]() =
    ZipWith[EitherErr[A], EitherErr[B], EitherErr[C], EitherErr[(A, B, C)]]((a, b, c) =>
      (a, b, c) match {
        case (Right(value1), Right(value2), Right(value3)) => Right((value1, value2, value3))
        case (Left(error), _, _)                           => Left(error)
        case (_, Left(error), _)                           => Left(error)
        case (_, _, Left(error))                           => Left(error)
      }
    )

  private[service] val zipContentWithSignedAttachmentAndMetadata = zip3[ZipContent, AttachmentBinary, AttachmentBinary]()

  private[service] val mapSignedZipContent = Flow[EitherErr[(ZipContent, AttachmentBinary, AttachmentBinary)]].map {
    _.map { case (zip, signedAttachment, signedMetadata) =>
      SignedZipContent(zip, signedAttachment, signedMetadata)
    }
  }

  override def signing: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits.*

        val broadcast = builder.add(Broadcast[EitherErr[ZipContent]](3))
        val input     = builder.add(partitionRequests[ZipContent]())
        val merge     = builder.add(Merge[EitherErr[SignedZipContent]](2))
        val zip       = builder.add(zipContentWithSignedAttachmentAndMetadata)

        input ~> errorTransform ~> merge
        input ~> broadcast

        broadcast ~> zip.in0
        broadcast ~> signAttachmentRequest ~> callDigitalSignatures ~> parseResponse ~> remapErrorSeverity ~> zip.in1
        broadcast ~> signAttachmentMetadataRequest ~> callDigitalSignatures ~> parseResponse ~> remapErrorSeverity ~> zip.in2

        zip.out ~> mapSignedZipContent ~> merge

        FlowShape(input.in, merge.out)
      }
    )
}
