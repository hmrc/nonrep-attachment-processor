package uk.gov.hmrc.nonrep.attachment
package service

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.util.{Try, Using}

trait Bundle {
  def createBundle: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed]

  def extractBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed]

  def extractMetadataField(zip: ZipContent, field: String): Option[String]
}

class BundleService extends Bundle {

  override def extractMetadataField(zip: ZipContent, field: String): Option[String] = {
    Try {
      zip
        .files
        .filter(_._1 == METADATA_FILE)
        .map(m => new String(m._2, "utf-8"))
        .head
        .parseJson
        .asJsObject
        .fields(field)
        .convertTo[String]
    }.toOption
  }

  override def createBundle: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed] =
    Flow[EitherErr[ZipContent]].map {
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
          AttachmentContent(content.info.copy(submissionId = extractMetadataField(content, "nrSubmissionId")) , ByteString(bytes.toByteArray))
        }
      }.toEither.left.flatMap(x => Left(ErrorMessage(s"Failure of creating zip archive for ${content.info.key} with ${x.getCause}"))))
    }

  override def extractBundle: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed] =
    Flow[EitherErr[AttachmentContent]].map {
      _.flatMap(attachment => {
        Using.Manager { use =>
          val zip = use(new ZipInputStream(new ByteArrayInputStream(attachment.content.toArray[Byte])))
          val content = LazyList.continually(zip.getNextEntry).takeWhile(_ != null).filter(!_.isDirectory).foldLeft(Seq[(String, Array[Byte])]())((seq, entry) => {
            val output = new ByteArrayOutputStream()
            zip.transferTo(output)
            seq :+ (entry.getName, output.toByteArray)
          })
          ZipContent(attachment.info, content)
        }.toEither.left.flatMap(x => Left(ErrorMessage(s"Failure of extracting zip archive for ${attachment.info.key} with ${x.getCause}")))
          .filterOrElse(_.files.nonEmpty, ErrorMessage(s"Failure of extracting zip archive for ${attachment.info.key} with no files found"))
      })
    }
}
