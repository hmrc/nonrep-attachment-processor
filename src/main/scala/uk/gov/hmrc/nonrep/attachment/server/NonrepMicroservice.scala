package uk.gov.hmrc.nonrep.attachment.server

import org.apache.pekko.Done
import org.apache.pekko.actor.CoordinatedShutdown
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.stream.scaladsl.Sink
import uk.gov.hmrc.nonrep.attachment.{AttachmentInfo, EitherErr}
import uk.gov.hmrc.nonrep.attachment.service.Processor
import uk.gov.hmrc.nonrep.attachment.utils.ErrorHandler

import scala.concurrent.Future
import scala.util.{Failure, Success}

class NonrepMicroservice()(using val system: ActorSystem[?], val config: ServiceConfig) extends ErrorHandler {
  val applicationSink: Sink[EitherErr[AttachmentInfo], Future[Done]] =
    Sink.foreach[EitherErr[AttachmentInfo]] {
      _.fold(
        errorHandler,
        attachmentInfo => system.log.info(s"Successful processing of attachment")
      )
    }

  lazy val attachmentsProcessor: Future[Done] = Processor(applicationSink).execute.run()
  lazy val routes: Routes = Routes(attachmentsProcessor)
  lazy val serverBinding: Future[Http.ServerBinding] = Http().newServerAt("0.0.0.0", config.port).bind(routes.serviceRoutes)

  def addServerBindingOnComplete(): Unit = {
    import system.executionContext

    serverBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info(s"Server '${ServiceConfig.appName}' is online at http://${address.getHostString}:${address.getPort}/ with configuration: {}")
        system.log.info(
          "Server '{}' is online at http://{}:{}/ with configuration: {}",
          ServiceConfig.appName,
          address.getHostString,
          address.getPort,
          config.config.toString
        )
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }
  }

  def addAttachmentsProcessorOnComplete(): Unit = {
    import system.executionContext

    attachmentsProcessor.onComplete {
      case Success(result) => system.log.info(s"Attachments processor finished its work ${result.toString}")
      case Failure(ex) => system.log.error(s"Attachments processor failed with ${ex.getMessage}", ex)
    }
  }

  def addCoordinatedShutdown(): Unit = {
    import system.executionContext
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "logShutdownInitiated") { () =>
      Future {
        system.log.info("initiating shutdown")
        Done
      }
    }
  }

}

object Main {

  /**
   * https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/java-dg-jvm-ttl.html
   */
  java.security.Security.setProperty("networkaddress.cache.ttl", "60")
  
  def main(args: Array[String]): Unit = {
    val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, s"NrsServer-${ServiceConfig.appName}")
    val config: ServiceConfig = ServiceConfig()

    val service: NonrepMicroservice = NonrepMicroservice()(using system, config)
    service.addServerBindingOnComplete()
    service.addAttachmentsProcessorOnComplete()
    service.addCoordinatedShutdown()

    import system.executionContext

    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "logShutdownInitiated") { () =>
      Future {
        system.log.info("initiating shutdown")
        Done
      }
    }
  }
}
