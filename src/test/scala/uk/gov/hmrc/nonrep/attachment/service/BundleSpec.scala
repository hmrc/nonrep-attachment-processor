package uk.gov.hmrc.nonrep.attachment
package service

import org.apache.pekko.stream.scaladsl.Keep
import org.apache.pekko.stream.testkit.scaladsl.{TestSink, TestSource}
import org.apache.pekko.util.ByteString

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class BundleSpec extends BaseSpec {

  import TestServices.*

  val zipper = new BundleService(using config)

  "Zipping service" should {
    "create a zip archive from content given" in {
      val attachmentId = testAttachmentId
      val messageId    = testSQSMessageIds.head
      val info         = AttachmentInfo(attachmentId, messageId, s"$attachmentId.zip")
      val zipContent   =
        SignedZipContent(info, Array.fill[Byte](1000)(Byte.MaxValue), Array.fill[Byte](1000)(Byte.MaxValue), sampleAttachmentMetadata)

      val source     = TestSource.probe[EitherErr[SignedZipContent]]
      val sink       = TestSink.probe[EitherErr[AttachmentContent]]
      val (pub, sub) = source.via(zipper.createBundle).toMat(sink)(Keep.both).run()
      pub.sendNext(Right(zipContent)).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      println(result)
      result.isRight                        shouldBe true
      result.toOption.get.info.attachmentId shouldBe attachmentId
      result.toOption.get.info.s3ObjectKey  shouldBe testS3ObjectKey
      result.toOption.get.content.size        should be > 0

      val zip     = new ZipInputStream(new ByteArrayInputStream(result.toOption.get.content.toArray[Byte]))
      val entries = LazyList.continually(zip.getNextEntry).takeWhile(_ != null).filter(!_.isDirectory).map(_.getName)
      entries.foreach(println)
      entries.find(_ == METADATA_FILE)          should not be empty
      entries.find(_ == ATTACHMENT_FILE)        should not be empty
      entries.find(_ == SIGNED_ATTACHMENT_FILE) should not be empty
    }

    "extract content from zip archive" in {
      val messageId = testSQSMessageIds.head
      val file      = ByteString(sampleAttachment)
      val info      = AttachmentInfo(testAttachmentId, messageId, testS3ObjectKey)
      val content   = AttachmentContent(info, file)

      val source = TestSource.probe[EitherErr[AttachmentContent]]
      val sink   = TestSink.probe[EitherErr[ZipContent]]

      val (pub, sub) = source.via(zipper.extractBundle).toMat(sink)(Keep.both).run()
      pub.sendNext(Right(content)).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      result.isRight                        shouldBe true
      result.toOption.get.info.attachmentId shouldBe info.attachmentId
      result.toOption.get.info.s3ObjectKey  shouldBe info.s3ObjectKey
      result.toOption.get.info.message      shouldBe messageId
    }

    "extract nrSubmissionId field from metadata" in {
      val attachmentId = testAttachmentId
      val messageId    = testSQSMessageIds.head
      val info         = AttachmentInfo(testAttachmentId, messageId, testS3ObjectKey)
      val zipContent   =
        SignedZipContent(info, Array.fill[Byte](1000)(Byte.MaxValue), Array.fill[Byte](1000)(Byte.MaxValue), sampleAttachmentMetadata)

      val source     = TestSource.probe[EitherErr[SignedZipContent]]
      val sink       = TestSink.probe[EitherErr[AttachmentContent]]
      val (pub, sub) = source.via(zipper.createBundle).toMat(sink)(Keep.both).run()
      pub.sendNext(Right(zipContent)).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      info.submissionId                     shouldBe None
      result.isRight                        shouldBe true
      result.toOption.get.info.submissionId shouldBe Some("eed095f9-7cd5-4a58-b74e-906c8d8807b5")
    }

    "fail on extracting non-zip archive" in {
      val messageId = testSQSMessageIds.head
      val file      = ByteString(Array.fill[Byte](10)(77))
      val info      = AttachmentInfo(testAttachmentId, messageId, testS3ObjectKey)
      val content   = AttachmentContent(info, file)

      val source = TestSource.probe[EitherErr[AttachmentContent]]
      val sink   = TestSink.probe[EitherErr[ZipContent]]

      val (pub, sub) = source.via(zipper.extractBundle).toMat(sink)(Keep.both).run()
      pub.sendNext(Right(content)).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      result.isLeft                    shouldBe true
      result.left.toOption.get.message shouldBe s"ADMIN_REQUIRED: Failure of extracting zip archive for $testS3ObjectKey with file attachment.data not found"
    }

    "fail on extracting missing metadata.json file archive" in {
      val messageId = testSQSMessageIds.head
      val file      = ByteString(sampleErrorAttachmentMissingMetadata)
      val info      = AttachmentInfo(testAttachmentId, messageId, testS3ObjectKey)
      val content   = AttachmentContent(info, file)

      val source = TestSource.probe[EitherErr[AttachmentContent]]
      val sink   = TestSink.probe[EitherErr[ZipContent]]

      val (pub, sub) = source.via(zipper.extractBundle).toMat(sink)(Keep.both).run()
      pub.sendNext(Right(content)).sendComplete()
      val result     = sub
        .request(1)
        .expectNext()

      result.isLeft                    shouldBe true
      result.left.toOption.get.message shouldBe s"ADMIN_REQUIRED: Failure of extracting zip archive for $testS3ObjectKey with file attachment.data not found"
    }

  }
}
