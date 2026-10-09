package uk.gov.hmrc.nonrep.attachment

import org.apache.pekko.Done
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.scalatest.{BeforeAndAfterEach, Inside}
import org.scalatest.time.{Millis, Seconds, Span}
import uk.gov.hmrc.nonrep.attachment.server.{NonrepMicroservice, Routes, ServiceConfig}

import scala.concurrent.Future

class ServiceIntErrorISpec extends BaseSpec with Inside with BeforeAndAfterEach {
  import TestServices.*

  var server: NonrepMicroservice = null
  val config: ServiceConfig      = new ServiceConfig(servicePort = 9343)
  val hostUrl                    = s"http://localhost:${config.port}"
  val service: String            = ServiceConfig.appName

  lazy val testKit                                                     = ActorTestKit()
  override def createActorSystem(): org.apache.pekko.actor.ActorSystem = testKit.system.toClassic

  override def afterAll(): Unit =
    if(server != null) {
      whenReady(server.serverBinding) {
        _.unbind()
      }
    }

  override protected def beforeEach(): Unit = {
    super.beforeAll()
    if(server != null) {
      whenReady(server.serverBinding) {
        _.unbind()
      }
    }
  }

  implicit val _: PatienceConfig = PatienceConfig(timeout = Span(5, Seconds), interval = Span(500, Millis))

  "attachment-processor service" should {
    "return a 500 response for GET requests to service /ping endpoint when attachments processor has stopped" ignore {

      server = NonrepMicroservice()(using system.toTyped, config)
      server.addServerBindingOnComplete()
      server.addAttachmentsProcessorOnComplete()
      server.addCoordinatedShutdown()

      whenReady(server.serverBinding) { _ => println("Processor started") }
      
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
    
    "return a 500 response for GET requests to service /ping endpoint when attachments processor has failed" in {
      class TestNonrepMicroservice()(using val system1: org.apache.pekko.actor.typed.ActorSystem[?], val config1: ServiceConfig) extends NonrepMicroservice()(using system1, config1) {
        val dunnyAttachmentsProcessor: Future[Done] = Future.failed(new Exception("Some ERROR"))
        override lazy val routes: Routes = Routes(dunnyAttachmentsProcessor)
      }

      val actorSystem = ActorTestKit().system
      server = TestNonrepMicroservice()(using actorSystem, config)
      whenReady(server.serverBinding) { _ => } // wait for things to start

      val responseFuture: Future[HttpResponse] = Http(actorSystem.toClassic).singleRequest(HttpRequest(uri = s"$hostUrl/${ServiceConfig.appName}/ping"))
      Thread.sleep(1000)
      whenReady(responseFuture) { res =>
        res.status shouldBe StatusCodes.InternalServerError
        whenReady(entityToString(res.entity)) { body =>
          body shouldBe "Processing of attachments is finished"
        }
      }
    }

    "return a 500 response for GET requests to service /ping endpoint when attachments processor has success" in {
      class TestNonrepMicroservice()(using val system1: org.apache.pekko.actor.typed.ActorSystem[?], val config1: ServiceConfig) extends NonrepMicroservice()(using system1, config1) {
        val dunnyAttachmentsProcessor:Future[Done] = Future.successful(Done)
        override lazy val routes: Routes = Routes(dunnyAttachmentsProcessor)
      }

      val actorSystem = ActorTestKit().system
      server = TestNonrepMicroservice()(using actorSystem, config)
      whenReady(server.serverBinding) { _ =>  } // wait for things to start

      val responseFuture: Future[HttpResponse] = Http(actorSystem.toClassic).singleRequest(HttpRequest(uri = s"$hostUrl/${ServiceConfig.appName}/ping"))
      Thread.sleep(1000)
      whenReady(responseFuture) { res =>
        res.status shouldBe StatusCodes.InternalServerError
        whenReady(entityToString(res.entity)) { body =>
          body shouldBe "Processing of attachments is finished"
        }
      }
    }

  }
}
