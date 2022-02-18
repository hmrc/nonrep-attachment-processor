package uk.gov.hmrc.nonrep.attachment
package service

import java.util.UUID

import akka.stream.scaladsl.Keep
import akka.stream.testkit.scaladsl.{TestSink, TestSource}

class SignSpec extends BaseSpec {

  import TestServices._

  "Sign service" should {
    import TestServices.success._

    "send attachment for signing with selected profile" in {
      val messageId = UUID.randomUUID().toString

      val attachmentInfo = AttachmentInfo(messageId, testAttachmentId)
      val zip = Right(ZipContent(attachmentInfo, Seq((ATTACHMENT_FILE, sampleAttachmentContent))))

      val source = TestSource.probe[EitherErr[ZipContent]]
      val sink = TestSink.probe[EitherErr[ZipContent]]
      val (pub, sub) = source.via(signService.signing()).toMat(sink)(Keep.both).run()
      pub.sendNext(zip).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isRight shouldBe true
      result.toOption.get.info.key shouldBe attachmentInfo.key
      result.toOption.get.info.message shouldBe messageId
      result.toOption.get.files.filter(_._1 == ATTACHMENT_FILE).head._2 shouldBe sampleAttachmentContent
      result.toOption.get.files.filter(_._1 == SIGNED_ATTACHMENT_FILE).head._2 shouldBe sampleSignedAttachmentContent
    }

    "fail on incorrect attachment bundle" in {
      val messageId = UUID.randomUUID().toString

      val attachmentInfo = AttachmentInfo(messageId, testAttachmentId)
      val zip = Right(ZipContent(attachmentInfo, Seq(("xxx", sampleAttachmentContent))))

      val source = TestSource.probe[EitherErr[ZipContent]]
      val sink = TestSink.probe[EitherErr[ZipContent]]
      val (pub, sub) = source.via(signService.signing()).toMat(sink)(Keep.both).run()
      pub.sendNext(zip).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.message should startWith("Invalid attachment bundle")
    }

    "leave original error messages severity level" in {
      val source = TestSource.probe[EitherErr[ZipContent]]
      val sink = TestSink.probe[EitherErr[ZipContent]]
      val (pub, sub) = source.via(signService.signing()).toMat(sink)(Keep.both).run()
      pub.sendNext(Left(ErrorMessage("test", ERROR))).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe ERROR
    }

    "return sign error messages with WARN severity" in {
      import TestServices.failure._
      val messageId = UUID.randomUUID().toString

      val attachmentInfo = AttachmentInfo(messageId, testAttachmentId)
      val zip = Right(ZipContent(attachmentInfo, Seq((ATTACHMENT_FILE, sampleAttachmentContent))))

      val source = TestSource.probe[EitherErr[ZipContent]]
      val sink = TestSink.probe[EitherErr[ZipContent]]
      val (pub, sub) = source.via(signService.signing()).toMat(sink)(Keep.both).run()
      pub.sendNext(zip).sendComplete()
      val result = sub
        .request(1)
        .expectNext()

      result.isLeft shouldBe true
      result.left.toOption.get.severity shouldBe WARN
    }
  }
}
