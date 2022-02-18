package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpEntity, HttpMethods, HttpRequest, HttpResponse}
import akka.stream.Supervision.stoppingDecider
import akka.stream.{ActorAttributes, FlowShape}
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Partition}
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import akka.util.ByteString

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

trait Sign {
  val TransactionIdHeader = "x-transaction-id"

  def signing(): Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed]
}

class SignService()(implicit val config: ServiceConfig,
                    implicit val system: ActorSystem[_]) extends Sign {

  private def partitionRequests[A]() =
    Partition[EitherErr[A]](2, {
      case Left(_)  => 0
      case Right(_) => 1
    })

  protected def parse(zip: EitherErr[ZipContent], response: HttpResponse): Future[EitherErr[ZipContent]] = {
    import system.executionContext
    if (response.status == OK) {
      response.entity.dataBytes
        .runFold(ByteString.empty)(_ ++ _)
        .map(_.toArray[Byte])
        .map(signed => zip.map(content => content.copy(files = content.files :+ (SIGNED_ATTACHMENT_FILE, signed))))
    } else {
      response.discardEntityBytes()
      val error = s"Response status ${response.status} from signatures service ${config.signaturesServiceHost}"
      Future.successful(Left(ErrorMessage(error)))
    }
  }

  val callDigitalSignatures: Flow[(HttpRequest, EitherErr[ZipContent]), (Try[HttpResponse], EitherErr[ZipContent]), Any] =
    if (config.isSignaturesServiceSecure)
      Http().cachedHostConnectionPoolHttps[EitherErr[ZipContent]](config.signaturesServiceHost, config.signaturesServicePort)
    else
      Http().cachedHostConnectionPool[EitherErr[ZipContent]](config.signaturesServiceHost, config.signaturesServicePort)

  val createRequest: Flow[EitherErr[ZipContent], (HttpRequest, EitherErr[ZipContent]), NotUsed] =
    Flow[EitherErr[ZipContent]].map { zip =>
      zip.filterOrElse(_.files.exists(_._1 == ATTACHMENT_FILE), ErrorMessage(s"Invalid attachment bundle for $zip")).fold(
        error => (HttpRequest(), Left(error)),
        content => {
          val headers = List(RawHeader(TransactionIdHeader, content.info.key))
          val request = HttpRequest(HttpMethods.POST, s"/${config.signaturesServiceHost}/cades/${config.signingProfile}", headers, HttpEntity(content.files.filter(_._1 == ATTACHMENT_FILE).head._2))
          (request, zip)
        })
    }

  val parseResponse: Flow[(Try[HttpResponse], EitherErr[ZipContent]), EitherErr[ZipContent], NotUsed] =
    Flow[(Try[HttpResponse], EitherErr[ZipContent])]
      .mapAsyncUnordered(8) {
        case (httpResponse, request) =>
          httpResponse match {
            case Success(response)  => parse(request, response)
            case Failure(exception) => Future.successful(Left(ErrorMessage(s"Failure connection to ${config.signaturesServiceHost} with ${exception.getMessage}")))
          }
      }
      .withAttributes(ActorAttributes.supervisionStrategy(stoppingDecider))

  val remapErrorSeverity: Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] =
    Flow[EitherErr[ZipContent]].map{
      _.left.map(error => ErrorMessage(error.message, WARN))
    }

  override def signing(): Flow[EitherErr[ZipContent], EitherErr[ZipContent], NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val input = builder.add(partitionRequests[ZipContent]())
        val merge = builder.add(Merge[EitherErr[ZipContent]](2))

        input ~> merge
        input ~> createRequest ~> callDigitalSignatures ~> parseResponse ~> remapErrorSeverity ~> merge

        FlowShape(input.in, merge.out)
      }
    )
}
