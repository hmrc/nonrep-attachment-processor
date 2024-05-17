package uk.gov.hmrc.nonrep.attachment
package service

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import spray.json.DefaultJsonProtocol._
import spray.json._
import uk.gov.hmrc.nonrep.attachment.server.ServiceConfig
import uk.gov.hmrc.nonrep.attachment.utils.ZipUtils

import scala.util.{Try, Using}

trait Bundle {
  def createBundle: Flow[EitherErr[SignedZipContent], EitherErr[AttachmentContent], NotUsed]

  def extractBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed]

  def extractMetadataField(metadata: Array[Byte], field: String): EitherErr[String]
}

class BundleService()(implicit val config: ServiceConfig) extends Bundle {

  override def extractMetadataField(metadata: Array[Byte], field: String): EitherErr[String] =
    Try {
      new String(metadata, "utf-8")
        .parseJson
        .asJsObject
        .fields(field)
        .convertTo[String]
    }.toEither.left.map(error => ErrorMessage(s"failed to extract $field from metadata.json file because ${error.getMessage}", Some(error), ERROR))

  override def createBundle: Flow[EitherErr[SignedZipContent], EitherErr[AttachmentContent], NotUsed] =
    Flow[EitherErr[SignedZipContent]].map {
      _.flatMap(content => {
        Using.Manager { use =>
          val bytes = use(new ByteArrayOutputStream())
          val zip = use(new ZipOutputStream(bytes))

          content.files.foreach {
            case (name, file) =>
              zip.putNextEntry(new ZipEntry(name))
              zip.write(file)
              zip.closeEntry()
          }
          extractMetadataField(content.metadata, "nrSubmissionId")
            .map(extractedValue => AttachmentContent(content.info.copy(submissionId = Some(extractedValue)) , ByteString(bytes.toByteArray)))
        }
      }.toEither.left.flatMap(exception =>
        Left(ErrorMessage(s"Failure of creating zip archive for ${content.info.s3ObjectKey} with ${exception.getCause}", Some(exception)))
      )).flatten
    }

  override def extractBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed] =
    Flow[EitherErr[AttachmentContent]].map {
      _.flatMap(attachment => {
        val contentByteArray = attachment.content.toArray[Byte]
        for {
          data      <- handleFileExtraction(attachment.info, ATTACHMENT_FILE, ZipUtils.readFileFromZip(contentByteArray, _))
          metadata  <- handleFileExtraction(attachment.info, METADATA_FILE, ZipUtils.readFileFromZip(contentByteArray, _))
        } yield ZipContent(attachment.info, data, metadata)
      })
    }

  private def handleFileExtraction(attachment: AttachmentInfo, filename: String, responseF: String => Either[Throwable, Option[Array[Byte]]]): EitherErr[Array[Byte]] = {
    responseF(filename)
      .left.map(e => ErrorMessage(s"Failure of extracting zip archive for attachment ${attachment.s3ObjectKey} relating to ${attachment.submissionId.getOrElse("INVALID")} with ${e.getCause}", Some(e), ERROR))
      .flatMap(_.toRight(ErrorMessage(s"ADMIN_REQUIRED: Failure of extracting zip archive for ${attachment.s3ObjectKey} with file $filename not found", None, ERROR)))
  }
}
