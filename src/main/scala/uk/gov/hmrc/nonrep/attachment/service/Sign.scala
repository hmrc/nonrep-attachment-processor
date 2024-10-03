package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.StatusCodes.OK
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Supervision.restartingDecider
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Merge, Partition}
import org.apache.pekko.stream.{ActorAttributes, FlowShape, OverflowStrategy}
import org.apache.pekko.util.ByteString
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait Sign {
  val TransactionIdHeader = "x-transaction-id"

  def signing: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed]
}

class SignService()(implicit val config: ServiceConfig,
                    implicit val system: ActorSystem[_]) extends Sign {

  private def partitionRequests[A]() =
    Partition[EitherErr[A]](2, {
      case Left(_) => 0
      case Right(_) => 1
    })

  protected def parse(zip: EitherErr[ZipContent], response: HttpResponse): Future[EitherErr[SignedZipContent]] = {
    import system.executionContext
    if (response.status == OK) {
      response.entity.dataBytes
        .runFold(ByteString.empty)(_ ++ _)
        .map(_.toArray[Byte])
        .map(signed => zip.map(SignedZipContent(_, signed)))
    } else {
      response.discardEntityBytes()
      val error = s"Response status ${response.status} from signatures service ${config.signaturesServiceHost}"
      Future.successful(Left(ErrorMessage(error)))
    }
  }

  val callDigitalSignatures: Flow[(HttpRequest, EitherErr[ZipContent]), (Try[HttpResponse], EitherErr[ZipContent]), Any] =
    (if (config.isSignaturesServiceSecure)
      Http().cachedHostConnectionPoolHttps[EitherErr[ZipContent]](config.signaturesServiceHost, config.signaturesServicePort)
    else
      Http().cachedHostConnectionPool[EitherErr[ZipContent]](config.signaturesServiceHost, config.signaturesServicePort))
      .buffer(config.signServiceBufferSize, OverflowStrategy.backpressure).async

  val createRequest: Flow[EitherErr[ZipContent], (HttpRequest, EitherErr[ZipContent]), NotUsed] =
    Flow[EitherErr[ZipContent]].map(zip =>
      zip.fold(
        error => HttpRequest() -> Left(error), //Have to keep http request outside either due to contract of HttpMethod
        {
          content =>
            val headers = List(RawHeader(TransactionIdHeader, content.info.attachmentId))
            val request = HttpRequest(HttpMethods.POST, s"/${config.signaturesServiceHost}/cades/${config.signingProfile}", headers, HttpEntity(content.attachment))
            (request, zip)
        }
      )
    )

  val parseResponse: Flow[(Try[HttpResponse], EitherErr[ZipContent]), EitherErr[SignedZipContent], NotUsed] =
    Flow[(Try[HttpResponse], EitherErr[ZipContent])]
      .mapAsyncUnordered(8) {
        case (httpResponse, request) =>
          httpResponse match {
            case Success(response) => parse(request, response)
            case Failure(exception) =>
              Future.successful(Left(ErrorMessage(s"Failure connection to ${config.signaturesServiceHost} with ${exception.getMessage}", Some(exception))))
          }
      }
      .withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))

  private val remapErrorSeverity: Flow[EitherErr[SignedZipContent], EitherErr[SignedZipContent], NotUsed] =
    Flow[EitherErr[SignedZipContent]].map {
      _.left.map(error => ErrorMessage(error.message, None, WARN))
    }

  private val errorTransform: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed] =
    Flow[EitherErr[ZipContent]]
      .map(_.fold[EitherErr[SignedZipContent]](
        error => Left(error),
        content => Left(ErrorMessage(s"partition failed for ${content.info.attachmentId}, this attachment should be getting signed", None, ERROR))
      ))

  override def signing: Flow[EitherErr[ZipContent], EitherErr[SignedZipContent], NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val input = builder.add(partitionRequests[ZipContent]())
        val merge = builder.add(Merge[EitherErr[SignedZipContent]](2))

        input ~> errorTransform ~> merge
        input ~> createRequest ~> callDigitalSignatures ~> parseResponse ~> remapErrorSeverity ~> merge

        FlowShape(input.in, merge.out)
      }
    )
}
