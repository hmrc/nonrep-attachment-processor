package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.stream.Supervision.stoppingDecider
import akka.stream.scaladsl.{Flow, GraphDSL, Merge, Partition}
import akka.stream.{ActorAttributes, FlowShape}
import akka.util.ByteString
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams
import software.amazon.awssdk.regions.Region
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.util.{Failure, Success, Try}

trait Update {

  def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]
}

class UpdateService()(implicit val config: ServiceConfig,
                      implicit val system: ActorSystem[_]) extends Update {

  import RequestsSigner._

  val signerParams: Aws4SignerParams = Aws4SignerParams.builder()
    .awsCredentials(DefaultCredentialsProvider.create().resolveCredentials()).ensure
    .signingRegion(Region.EU_WEST_2).ensure
    .signingName("es").ensure
    .build()

  private def partitionRequests[A]() =
    Partition[EitherErr[A]](2, {
      case Left(_) => 0
      case Right(_) => 1
    })

  protected def parse(archived: EitherErr[ArchivedAttachment], response: HttpResponse): EitherErr[ArchivedAttachment] = {
    import system.executionContext
    if (response.status == OK) {
      response.entity.dataBytes
        .runFold(ByteString.empty)(_ ++ _)
        .map(_.utf8String)
        .foreach(response => system.log.info(s"Metastore response $response"))
      archived
    } else {
      response.discardEntityBytes()
      val error = s"Response status ${response.status} from ES service ${config.elasticSearchHost}"
      Left(ErrorMessage(error))
    }
  }

  val createRequest: Flow[EitherErr[ArchivedAttachment], (HttpRequest, EitherErr[ArchivedAttachment]), NotUsed] =
    Flow[EitherErr[ArchivedAttachment]].map { attachment =>
      (attachment.toOption.map(archived => {
        val path = s"/${archived.info.notableEvent}/index/${archived.info.submissionId.getOrElse(new IllegalStateException("Submission ID must be present"))}/_update"
        val body = s"""{"doc": {"glacier": {"attachments":{ "${archived.info.key}": { "vaultName": "${archived.vaultName}", "archiveId": "${archived.archiveId}"}}}}}"""
        val request = createSignedRequest(HttpMethods.POST, config.elasticSearchUri, path, body, signerParams)
        system.log.info(s"Update metastore request for: [${archived.info.key}], path: [$path], body: [$body] and request: [$request]")
        request
      }).getOrElse(throw new RuntimeException("Error creating ES request")), attachment)
    }

  val callMetastore: Flow[(HttpRequest, EitherErr[ArchivedAttachment]), (Try[HttpResponse], EitherErr[ArchivedAttachment]), Any] =
    if (config.isElasticSearchProtocolSecure)
      Http().cachedHostConnectionPoolHttps[EitherErr[ArchivedAttachment]](config.elasticSearchHost)
    else
      Http().cachedHostConnectionPool[EitherErr[ArchivedAttachment]](config.elasticSearchHost)

  val parseResponse: Flow[(Try[HttpResponse], EitherErr[ArchivedAttachment]), EitherErr[ArchivedAttachment], NotUsed] =
    Flow[(Try[HttpResponse], EitherErr[ArchivedAttachment])].map {
      case (httpResponse, request) =>
        httpResponse match {
          case Success(response) => parse(request, response)
          case Failure(exception) => Left(ErrorMessage(s"Failure connection to ${config.elasticSearchHost} with ${exception.getMessage}"))
        }
    }
      .withAttributes(ActorAttributes.supervisionStrategy(stoppingDecider))

  val remapAttachmentInfo: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] =
    Flow[EitherErr[ArchivedAttachment]].map {
      _.map(_.info)
    }

  override def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] =
    Flow.fromGraph(
      GraphDSL.create() { implicit builder =>
        import GraphDSL.Implicits._

        val input = builder.add(partitionRequests[ArchivedAttachment]())
        val merge = builder.add(Merge[EitherErr[ArchivedAttachment]](2))
        val remapAttachmentInfoShape = builder.add(remapAttachmentInfo)

        input ~> merge
        input ~> createRequest ~> callMetastore ~> parseResponse ~> merge ~> remapAttachmentInfoShape.in

        FlowShape(input.in, remapAttachmentInfoShape.out)
      }
    )

}