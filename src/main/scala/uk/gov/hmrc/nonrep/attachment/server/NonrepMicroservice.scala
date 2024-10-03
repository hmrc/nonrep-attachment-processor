package uk.gov.hmrc.nonrep.attachment
package server

import org.apache.pekko.Done
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.stream.scaladsl.Sink
import uk.gov.hmrc.nonrep.attachment.service.Processor
import uk.gov.hmrc.nonrep.attachment.utils.ErrorHandler

import scala.concurrent.Future
import scala.util.{Failure, Success}

class NonrepMicroservice()(implicit val system: ActorSystem[_], config: ServiceConfig) extends ErrorHandler {

  val applicationSink: Sink[EitherErr[AttachmentInfo], Future[Done]] = Sink.foreach[EitherErr[AttachmentInfo]] {
    _.fold(
      errorHandler,
      attachmentInfo =>
        system.log.info(s"Successful processing of attachment ${attachmentInfo.attachmentId}")
    )
  }

  val attachmentsProcessor: Future[Done] = Processor(applicationSink).execute.run()

  val routes = Routes(attachmentsProcessor)

  val serverBinding: Future[Http.ServerBinding] = Http().newServerAt("0.0.0.0", config.port).bind(routes.serviceRoutes)
}

object Main {
  /**
   * https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/java-dg-jvm-ttl.html
   */
  java.security.Security.setProperty("networkaddress.cache.ttl", "60")

  implicit val config: ServiceConfig = new ServiceConfig()

  implicit val system: ActorSystem[Nothing] = ActorSystem[Nothing](Behaviors.empty, s"NrsServer-${config.appName}")

  val service = new NonrepMicroservice()

  def main(args: Array[String]): Unit = {
    import system.executionContext

    service.serverBinding.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        system.log.info("Server '{}' is online at http://{}:{}/ with configuration: {}", config.appName, address.getHostString, address.getPort, config.toString)
      case Failure(ex) =>
        system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
        system.terminate()
    }

    service.attachmentsProcessor.onComplete {
      case Success(result) => system.log.info(s"Attachments processor finished its work ${result.toString}")
      case Failure(ex) => system.log.error(s"Attachments processor failed with ${ex.getMessage}", ex)
    }
  }
}