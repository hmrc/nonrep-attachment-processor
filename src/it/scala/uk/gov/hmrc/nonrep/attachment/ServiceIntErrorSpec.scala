package uk.gov.hmrc.nonrep.attachment

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.scalatest.Inside
import org.scalatest.time.{Millis, Seconds, Span}
import uk.gov.hmrc.nonrep.BuildInfo
import uk.gov.hmrc.nonrep.attachment.app.json.JsonFormats.buildVersionJsonFormat
import uk.gov.hmrc.nonrep.attachment.server.{NonrepMicroservice, ServiceConfig}

import scala.concurrent.Future

class ServiceIntErrorSpec extends BaseSpec with Inside {
  import TestServices.*

  var server: NonrepMicroservice = null
  val config: ServiceConfig      = new ServiceConfig(servicePort = 9343)
  val hostUrl                    = s"http://localhost:${config.port}"
  val service: String            = config.appName

  lazy val testKit                                                     = ActorTestKit()
  override def createActorSystem(): org.apache.pekko.actor.ActorSystem = testKit.system.toClassic

  override def beforeAll(): Unit =
    server = NonrepMicroservice()(using system.toTyped, config)

  override def afterAll(): Unit =
    whenReady(server.serverBinding) {
      _.unbind()
    }

  implicit val _: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  "attachment-processor service" should {
    "return a 500 response for GET requests to service /ping endpoint when attachments processor has stopped" in {
      import scala.jdk.FutureConverters.*
      server.attachmentsProcessor.asJava.toCompletableFuture.cancel(true)
      Thread.sleep(1000)
      val responseFuture: Future[HttpResponse] = Http(system).singleRequest(HttpRequest(uri = s"$hostUrl/${config.appName}/ping"))
      whenReady(responseFuture) { res =>
        res.status shouldBe StatusCodes.InternalServerError
        whenReady(entityToString(res.entity)) { body =>
          body shouldBe "Processing of attachments is finished"
        }
      }
    }
  }
}
