package uk.gov.hmrc.nonrep.attachment
package service

import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream

import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSink, TestSource}
import akka.util.ByteString

class ZipperSpec extends BaseSpec {

  import TestServices._

  val zipper = new ZipperService

  "Zipping service" should {
    "create a zip archive from content given" in {
      val attachmentId = UUID.randomUUID().toString
      val attachment = (attachmentId, Array.fill[Byte](1000)(Byte.MaxValue))
      val metadata = (METADATA_FILE, s"{attachmentId = $attachmentId}".getBytes("utf-8"))
      val messageId = UUID.randomUUID().toString
      val info = AttachmentInfo(messageId, attachmentId)
      val zipContent = ZipContent(info, Seq(metadata, attachment))

      val source = TestSource.probe[EitherErr[ZipContent]]
      val sink = TestSink.probe[EitherErr[AttachmentContent]]
      val (pub, sub) = source.via(zipper.zip()).toMat(sink)(Keep.both).run()
      pub.sendNext(Right(zipContent)).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.info.key shouldBe attachmentId
      result.toOption.get.content.size should be > 0

      val zip = new ZipInputStream(new ByteArrayInputStream(result.toOption.get.content.toArray[Byte]))
      val entries = LazyList.continually(zip.getNextEntry).takeWhile(_ != null).filter(!_.isDirectory).map(_.getName)
      entries.filter(_ == METADATA_FILE).headOption should not be empty
      entries.filter(_ == attachmentId).headOption should not be empty
    }

    "extract content from zip archive" in {
      val messageId = UUID.randomUUID().toString
      val attachmentId = "738bcba6-7f9e-11ec-8768-3f8498104f38"
      val file = ByteString(sampleAttachment)
      val info = AttachmentInfo(messageId, attachmentId)
      val content = AttachmentContent(info, file)

      val source = TestSource.probe[EitherErr[AttachmentContent]]
      val sink = TestSink.probe[EitherErr[ZipContent]]

      val (pub, sub) = source.via(zipper.unzip()).toMat(sink)(Keep.both).run()
      pub.sendNext(Right(content)).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.info.key shouldBe attachmentId
      result.toOption.get.info.message shouldBe messageId
      result.toOption.get.files.size shouldBe 2
      result.toOption.get.files.filter(_._1 == METADATA_FILE).isEmpty shouldBe false
      result.toOption.get.files.filter(_._1 == ATTACHMENT_FILE).isEmpty shouldBe false
    }

    "fail on extracting non-zip archive" in {
      val messageId = UUID.randomUUID().toString
      val attachmentId = "738bcba6-7f9e-11ec-8768-3f8498104f38"
      val file = ByteString(Array.fill[Byte](10)(77))
      val info = AttachmentInfo(messageId, attachmentId)
      val content = AttachmentContent(info, file)

      val source = TestSource.probe[EitherErr[AttachmentContent]]
      val sink = TestSink.probe[EitherErr[ZipContent]]

      val (pub, sub) = source.via(zipper.unzip()).toMat(sink)(Keep.both).run()
      pub.sendNext(Right(content)).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.message shouldBe s"Failure of extracting zip archive for $attachmentId with no files found"
    }

  }
}
