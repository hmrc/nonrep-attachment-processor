package uk.gov.hmrc.nonrep.attachment
package server

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http

import scala.util.{Failure, Success}

object NonrepMicroservice {
  def apply(routes: Routes)(implicit system: ActorSystem[_], config: ServiceConfig) = new NonrepMicroservice(routes)
}

class NonrepMicroservice(routes: Routes)(implicit val system: ActorSystem[_], config: ServiceConfig) {
  import system.executionContext

  val serverBinding = Http().newServerAt("0.0.0.0", config.port).bind(routes.serviceRoutes)
  serverBinding.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      system.log.info("Server '{}' is online at http://{}:{}/ with configuration: {}", config.appName, address.getHostString, address.getPort, config.toString)
    case Failure(ex) =>
      system.log.error("Failed to bind HTTP endpoint, terminating system", ex)
      system.terminate()
  }
}

object Main {
  /**
   * https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/java-dg-jvm-ttl.html
   */
  java.security.Security.setProperty("networkaddress.cache.ttl", "60")

  implicit val config: ServiceConfig = new ServiceConfig()

  def main(args: Array[String]): Unit = {
    val rootBehavior = Behaviors.setup[Nothing] { context =>


      val routes = Routes()(context.system, implicitly)

      NonrepMicroservice(routes)(context.system, config)

      Behaviors.empty
    }
    implicit val system = ActorSystem[Nothing](rootBehavior, s"NrsServer-${config.appName}")

  }
}