package uk.gov.hmrc.nonrep.attachment

import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

trait BaseSpec extends AnyWordSpec with ScalaFutures with ScalatestRouteTest with Matchers {

}
