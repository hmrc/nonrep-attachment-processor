package uk.gov.hmrc.nonrep.attachment.service

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.signer.params.Aws4SignerParams
import software.amazon.awssdk.regions.Region

trait Index {

}

class IndexService extends Index {
  import RequestsSigner._

  val signerParams = Aws4SignerParams.builder()
    .awsCredentials(DefaultCredentialsProvider.create().resolveCredentials()).ensure
    .signingRegion(Region.EU_WEST_2).ensure
    .signingName("es").ensure
    .build()


}
