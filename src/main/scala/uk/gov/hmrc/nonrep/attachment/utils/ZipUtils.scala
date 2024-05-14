package uk.gov.hmrc.nonrep.attachment.utils

import uk.gov.hmrc.nonrep.attachment.{ERROR, EitherErr, ErrorMessage, ZipContent}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util.zip.{ZipEntry, ZipInputStream, ZipOutputStream}
import scala.util.{Try, Using}

object ZipUtils {

//  val streamToString: Array[Byte] => String = stream => new String(stream)
//  val streamToArrayOfBytes: Array[Byte] => Array[Byte] = stream => stream

//  def readFromZipAsString(zipFile: Array[Byte], fileName: String): EitherErr[String] =
//    Try {
//      readFileFromZip(zipFile, fileName, streamToString)
//    }.toEither.flatMap {
//      case LazyList() => Left(ErrorMessage(s"Zip entry with name $fileName is empty"))
//      case LazyList(x) => Right(x)
//    }.left.map {
//      case thr: Exception        => ErrorMessage(s"Failed while trying to read zip entry $fileName with error: ${thr.getMessage}", Some(thr))
//      case Left(x: ErrorMessage) => x
//      case _                     => ErrorMessage(s"Failed while trying to read zip entry $fileName")
//    }

  val DEFAULT_ZIP_ENTRY_SIZE = 10000000
  private def defaultedZipEntrySize(e: ZipEntry) = if (e.getSize > 0) e.getSize.toInt else DEFAULT_ZIP_ENTRY_SIZE

  def readFileFromZip[T](input: Array[Byte], name: String): Either[Throwable, Option[Array[Byte]]] =
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
//       .left.map(e => ErrorMessage(s"error reading $name from bundle due to ${e}", Some(e), ERROR))
//       .flatMap(_.toRight(ErrorMessage(s"can not find file $name in zip file", None, ERROR)))


  /**
   * Method which creates zip archive from provided entries
   */
//  def createZip(entries: BundleEntry*): Array[Byte] = {
//    val stream = new ByteArrayOutputStream
//    val zip = new ZipOutputStream(stream)
//
//    entries.foreach(entry => {
//      val zipEntry = new ZipEntry(entry.name)
//      zip.putNextEntry(zipEntry)
//      zip.write(entry.content)
//      zip.closeEntry()
//    })
//
//    zip.close()
//    stream.toByteArray
//  }

}