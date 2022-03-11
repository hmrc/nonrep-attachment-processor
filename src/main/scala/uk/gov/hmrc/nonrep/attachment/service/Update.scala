package uk.gov.hmrc.nonrep.attachment
package service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.Flow
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams
import software.amazon.awssdk.regions.Region
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig

import scala.util.Try

trait Update {

  def updateMetastore(): Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed]
}

class UpdateService()(implicit val config: ServiceConfig,
                      implicit val system: ActorSystem[_]) extends Update {

  import RequestsSigner._

  val signerParams: Aws4SignerParams = Aws4SignerParams.builder()
    .awsCredentials(DefaultCredentialsProvider.create().resolveCredentials()).ensure
    .signingRegion(Region.EU_WEST_2).ensure
    .signingName("es").ensure
    .build()

  override def updateMetastore(): Flow[EitherErr[ArchivedAttachment], EitherErr[AttachmentInfo], NotUsed] =
    Flow[EitherErr[ArchivedAttachment]].map{ attachment =>
      attachment.map(_.info)
    }

  val callMetastore: Flow[(HttpRequest, EitherErr[ArchivedAttachment]), (Try[HttpResponse], EitherErr[ArchivedAttachment]), Any] =
    if (config.isElasticSearchProtocolSecure)
      Http().cachedHostConnectionPoolHttps[EitherErr[ArchivedAttachment]](config.elasticSearchHost)
    else
      Http().cachedHostConnectionPool[EitherErr[ArchivedAttachment]](config.elasticSearchHost)


}
