package uk.gov.hmrc.nonrep.attachment

import akka.actor.testkit.typed.scaladsl.ActorTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse, StatusCodes}
import org.scalatest.Inside
import org.scalatest.time.{Millis, Span}
import uk.gov.hmrc.nonrep.BuildInfo
import uk.gov.hmrc.nonrep.attachment.server.{NonrepMicroservice, Routes, ServiceConfig}
import uk.gov.hmrc.nonrep.attachment.utils.JsonFormats._

import scala.concurrent.Future

class ServiceIntSpec extends BaseSpec with Inside {
  import TestServices._

  var server: NonrepMicroservice = null
  implicit val config: ServiceConfig = new ServiceConfig(servicePort = 9000)
  val hostUrl = s"http://localhost:${config.port}"
  val service = config.appName

  lazy val testKit = ActorTestKit()
  implicit val typedSystem = testKit.system

  override def createActorSystem(): akka.actor.ActorSystem = testKit.system.toClassic

  implicit val patience: PatienceConfig = PatienceConfig(Span(5000, Millis), Span(100, Millis))

  override def beforeAll() = {
    server = NonrepMicroservice(Routes())
  }

  override def afterAll(): Unit = {
    whenReady(server.serverBinding) {
      _.unbind()
    }
  }

  "attachment-processor service" should {

    "return version information for GET request to service /version endpoint" in {
      val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = s"$hostUrl/${config.appName}/version"))
      whenReady(responseFuture) { res =>
        res.status shouldBe StatusCodes.OK
        whenReady(entityToString(res.entity)) { body =>
          body shouldBe buildVersionJsonFormat.write(BuildVersion(version = BuildInfo.version)).toString
        }
      }
    }

    "return a 'pong' response for GET requests to service /ping endpoint" in {
      val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = s"$hostUrl/${config.appName}/ping"))
      whenReady(responseFuture) { res =>
        res.status shouldBe StatusCodes.OK
        whenReady(entityToString(res.entity)) { body =>
          body shouldBe "pong"
        }
      }
    }

    "return a 'pong' response for GET requests to /ping endpoint" in {
      val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = s"$hostUrl/ping"))
      whenReady(responseFuture) { res =>
        res.status shouldBe StatusCodes.OK
        whenReady(entityToString(res.entity)) { body =>
          body shouldBe "pong"
        }
      }
    }

    "return jvm metrics" in {
      val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = s"$hostUrl/metrics"))
      whenReady(responseFuture) { res =>
        res.status shouldBe StatusCodes.OK
        whenReady(entityToString(res.entity)) { body =>
          body
            .split('\n')
            .filter(_.startsWith("# TYPE ")) should contain allElementsOf Seq(
            "# TYPE jvm_memory_objects_pending_finalization gauge",
            "# TYPE jvm_memory_bytes_used gauge",
            "# TYPE jvm_memory_bytes_committed gauge",
            "# TYPE jvm_memory_bytes_max gauge",
            "# TYPE jvm_memory_bytes_init gauge",
            "# TYPE jvm_memory_pool_bytes_used gauge",
            "# TYPE jvm_memory_pool_bytes_committed gauge",
            "# TYPE jvm_memory_pool_bytes_max gauge",
            "# TYPE jvm_memory_pool_bytes_init gauge",
            "# TYPE jvm_memory_pool_collection_used_bytes gauge",
            "# TYPE jvm_memory_pool_collection_committed_bytes gauge",
            "# TYPE jvm_memory_pool_collection_max_bytes gauge",
            "# TYPE jvm_memory_pool_collection_init_bytes gauge",
            "# TYPE jvm_gc_collection_seconds summary",
            "# TYPE jvm_memory_heap_committed gauge",
            "# TYPE jvm_memory_non_heap_used gauge",
            "# TYPE jvm_memory_pools_Compressed_Class_Space_usage gauge",
            "# TYPE jvm_threads_waiting_count gauge",
            "# TYPE jvm_memory_total_committed gauge",
            "# TYPE jvm_memory_heap_usage gauge",
            "# TYPE jvm_attribute_uptime gauge",
            "# TYPE jvm_memory_total_used gauge",
            "# TYPE jvm_threads_timed_waiting_count gauge",
            "# TYPE jvm_memory_heap_used gauge",
            "# TYPE jvm_memory_non_heap_committed gauge",
            "# TYPE jvm_memory_non_heap_usage gauge",
            "# TYPE jvm_memory_heap_init gauge",
            "# TYPE jvm_memory_pools_Metaspace_usage gauge",
            "# TYPE jvm_threads_count gauge",
            "# TYPE jvm_threads_new_count gauge",
            "# TYPE jvm_memory_non_heap_init gauge",
            "# TYPE jvm_memory_total_max gauge",
            "# TYPE jvm_threads_runnable_count gauge",
            "# TYPE jvm_threads_terminated_count gauge",
            "# TYPE jvm_memory_heap_max gauge",
            "# TYPE jvm_memory_non_heap_max gauge",
            "# TYPE jvm_memory_total_init gauge",
            "# TYPE jvm_threads_daemon_count gauge",
            "# TYPE jvm_threads_blocked_count gauge",
            "# TYPE jvm_buffer_pool_used_bytes gauge",
            "# TYPE jvm_buffer_pool_capacity_bytes gauge",
            "# TYPE jvm_buffer_pool_used_buffers gauge",
            "# TYPE jvm_classes_loaded_total counter",
            "# TYPE jvm_classes_unloaded_total counter",
            "# TYPE jvm_memory_pool_allocated_bytes_total counter",
            "# TYPE jvm_threads_current gauge",
            "# TYPE jvm_threads_daemon gauge",
            "# TYPE jvm_threads_peak gauge",
            "# TYPE jvm_threads_started_total counter",
            "# TYPE jvm_threads_deadlocked gauge",
            "# TYPE jvm_threads_deadlocked_monitor gauge",
            "# TYPE jvm_threads_state gauge",
            "# TYPE process_cpu_seconds_total counter",
            "# TYPE process_start_time_seconds gauge",
            "# TYPE process_open_fds gauge",
            "# TYPE process_max_fds gauge",
            "# TYPE jvm_info gauge"
          )
        }
      }
    }
  }
}