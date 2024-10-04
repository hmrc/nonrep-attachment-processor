package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.NotUsed
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.StatusCodes.{Created, OK}
import org.apache.pekko.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import org.apache.pekko.stream.Supervision.restartingDecider
import org.apache.pekko.stream.scaladsl.{Flow, GraphDSL, Merge, Partition, Source}
import org.apache.pekko.stream.{ActorAttributes, FlowShape, OverflowStrategy}
import org.apache.pekko.util.ByteString
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.service.RequestsSigner._

import scala.util.{Failure, Success, Try}

trait Update {
  def updateMetastore: Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]

  def signerParams: Source[RequestsSignerParams, NotUsed]
}

class UpdateService()(implicit val config: ServiceConfig,
                      implicit val system: ActorSystem[_]) extends Update {


  private[service] def createRequestsSignerParams = {
    system.log.info("AWS Signer parameters has been refreshed")
    new RequestsSignerParams()
  }

  override val signerParams: Source[RequestsSignerParams, NotUsed] =
    Source.unfold(createRequestsSignerParams)(params => Some(if (params.expired()) createRequestsSignerParams else params, params))

  private def partitionRequests[A]() =
    Partition[EitherErr[A]](2, {
      case Left(_) => 0
      case Right(_) => 1
    })

  protected def parse(archived: EitherErr[ArchivedAttachment], response: HttpResponse): EitherErr[ArchivedAttachment] = {
    import system.executionContext
    if (response.status == OK || response.status == Created) {
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

  val createRequest: Flow[(EitherErr[ArchivedAttachment], RequestsSignerParams), (HttpRequest, EitherErr[ArchivedAttachment]), NotUsed] =
    Flow[(EitherErr[ArchivedAttachment], RequestsSignerParams)].map { case (attachment, signerParams) =>
      (attachment.toOption.map(archived => {
        val submissionId = archived.info.submissionId.getOrElse(new IllegalStateException("Submission ID must be present"))
        val path = s"/${archived.info.notableEvent}-attachments/index/${archived.info.attachmentId}"
        val body = s"""{ "attachmentId": "${archived.info.attachmentId}", "nrSubmissionId": "$submissionId", "glacier": { "vaultName": "${archived.vaultName}", "archiveId": "${archived.archiveId}"}}"""
        val request = createSignedRequest(HttpMethods.POST, config.elasticSearchUri, path, body, signerParams.params)
        system.log.info(s"Update metastore request for: [${archived.info.attachmentId}], path: [$path], body: [$body] and request: [$request]")
        request
      }).getOrElse(throw new RuntimeException("Error creating ES request")), attachment)
    }

  val callMetastore: Flow[(HttpRequest, EitherErr[ArchivedAttachment]), (Try[HttpResponse], EitherErr[ArchivedAttachment]), Any] =
    (if (config.isElasticSearchProtocolSecure)
      Http().cachedHostConnectionPoolHttps[EitherErr[ArchivedAttachment]](config.elasticSearchHost)
    else
      Http().cachedHostConnectionPool[EitherErr[ArchivedAttachment]](config.elasticSearchHost))
      .buffer(config.esServiceBufferSize, OverflowStrategy.backpressure).async

  val parseResponse: Flow[(Try[HttpResponse], EitherErr[ArchivedAttachment]), EitherErr[ArchivedAttachment], NotUsed] =
    Flow[(Try[HttpResponse], EitherErr[ArchivedAttachment])].map {
      case (httpResponse, request) =>
        httpResponse match {
          case Success(response)  => parse(request, response)
          case Failure(exception) => Left(ErrorMessage(s"Failure connection to ${config.elasticSearchHost} with ${exception.getMessage}", Some(exception)))
        }
    }
      .withAttributes(ActorAttributes.supervisionStrategy(restartingDecider))

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
        input.zip(signerParams) ~> createRequest ~> callMetastore ~> parseResponse ~> merge ~> remapAttachmentInfoShape.in

        FlowShape(input.in, remapAttachmentInfoShape.out)
      }
    )

}