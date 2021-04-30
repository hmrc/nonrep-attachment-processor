package uk.gov.hmrc.nonrep.attachment

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.http.scaladsl.model.ResponseEntity
import akka.util.ByteString

import scala.concurrent.ExecutionContext

object TestServices {

  lazy val testKit = ActorTestKit()

  implicit def typedSystem = testKit.system

  def entityToString(entity: ResponseEntity)(implicit ec: ExecutionContext) =
    entity.dataBytes.runFold(ByteString(""))(_ ++ _).map(_.utf8String)

}