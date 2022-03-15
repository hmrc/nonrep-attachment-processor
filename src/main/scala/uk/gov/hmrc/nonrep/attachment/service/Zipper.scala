package uk.gov.hmrc.nonrep.attachment
package service

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import scala.util.Using

trait Zipper {
  def zip: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed]

  def unzip: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed]
}

class ZipperService extends Zipper {

  override def zip: Flow[EitherErr[ZipContent], EitherErr[AttachmentContent], NotUsed] =
    Flow[EitherErr[ZipContent]].map {
      _.flatMap(content => {
        Using.Manager { use =>
          val bytes = use(new ByteArrayOutputStream())
          val zip = use(new ZipOutputStream(bytes))
          content.files.foreach {
            case (name, file) => {
              zip.putNextEntry(new ZipEntry(name))
              zip.write(file)
              zip.closeEntry()
            }
          }
          AttachmentContent(content.info, ByteString(bytes.toByteArray))
        }
      }.toEither.left.flatMap(x => Left(ErrorMessage(s"Failure of creating zip archive for ${content.info.key} with ${x.getCause}"))))
    }

  override def unzip: Flow[EitherErr[AttachmentContent], EitherErr[ZipContent], NotUsed] =
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
          .filterOrElse(_.files.size > 0, ErrorMessage(s"Failure of extracting zip archive for ${attachment.info.key} with no files found"))
      })
    }
}
