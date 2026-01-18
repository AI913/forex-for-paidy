package forex

import scala.concurrent.ExecutionContext
import cats.effect._
import forex.config._
// import forex.services.rates.interpreters.OneFrameLive
import fs2.Stream
import org.http4s.blaze.server.BlazeServerBuilder
// import cats.implicits._

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    new Application[IO].stream(executionContext).compile.drain.as(ExitCode.Success)

}

class Application[F[_]: ConcurrentEffect: Timer] {

  def stream(ec: ExecutionContext): Stream[F, Unit] =
    for {
      config <- Config.stream("app")
      module = new Module[F](config)
      _ <- Stream.eval(module.startBackgroundRefresh)
      _ <- BlazeServerBuilder[F](ec)
            .bindHttp(config.http.port, config.http.host)
            .withHttpApp(module.httpApp)
            .serve
    } yield ()
}
