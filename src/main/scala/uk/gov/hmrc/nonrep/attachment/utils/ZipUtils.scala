package uk.gov.hmrc.nonrep.attachment.utils

import uk.gov.hmrc.nonrep.attachment.{ERROR, EitherErr, ErrorMessage, ZipContent}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import scala.util.{Try, Using}

object ZipUtils {

  def readFileFromZip(input: Array[Byte], name: String): Either[Throwable, Option[Array[Byte]]] =
     Using.Manager { use =>
      val zip = use(new ZipInputStream(new ByteArrayInputStream(input)))
      val content = LazyList
        .continually(zip.getNextEntry)
        .takeWhile(_ != null)
        .filter(!_.isDirectory)
        .find(_.getName == name)
        .map { _ =>
            val output = new ByteArrayOutputStream()
            zip.transferTo(output)
            output.toByteArray
        }

      content
    }.toEither
}