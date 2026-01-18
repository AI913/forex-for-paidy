package forex

import org.http4s.client.blaze.BlazeClientBuilder
import scala.concurrent.ExecutionContext.Implicits.global
import cats.effect.{Concurrent, ConcurrentEffect, Timer}
import cats.effect.syntax.concurrent._
import cats.syntax.functor._
import cats.syntax.flatMap._
import forex.config.ApplicationConfig
import forex.http.rates.RatesHttpRoutes
import forex.services._
import forex.programs._
import org.http4s._
import org.http4s.implicits._
import org.http4s.server.middleware.{ AutoSlash, Timeout }
import forex.services.rates.interpreters.OneFrameLive  // ← Add this import

class Module[F[_]: Timer: ConcurrentEffect](config: ApplicationConfig) {

  // private val ratesService: RatesService[F] = RatesServices.dummy[F]

  // When ready, switch to live:
  private val ratesService: RatesService[F] = {
    val clientResource = BlazeClientBuilder[F](global).resource
    val (client, _) = implicitly[ConcurrentEffect[F]].toIO(clientResource.allocated).unsafeRunSync()
    RatesServices.live[F](client)
  }

  private val ratesProgram: RatesProgram[F] = RatesProgram[F](ratesService)

  private val ratesHttpRoutes: HttpRoutes[F] = new RatesHttpRoutes[F](ratesProgram).routes

  type PartialMiddleware = HttpRoutes[F] => HttpRoutes[F]
  type TotalMiddleware   = HttpApp[F] => HttpApp[F]

  private val routesMiddleware: PartialMiddleware = {
    { http: HttpRoutes[F] =>
      AutoSlash(http)
    }
  }

  private val appMiddleware: TotalMiddleware = { http: HttpApp[F] =>
    Timeout(config.http.timeout)(http)
  }

  private val http: HttpRoutes[F] = ratesHttpRoutes

  val httpApp: HttpApp[F] = appMiddleware(routesMiddleware(http).orNotFound)

  // Public method to start background refresh (returns F[Unit] for use in Stream)
  def startBackgroundRefresh: F[Unit] =
    ratesService match {
      case live: OneFrameLive[F] => live.refreshAll.flatMap { _ =>
        live.refreshStream.compile.drain.start.void
      }
      case _ =>
        Concurrent[F].unit
    }
}