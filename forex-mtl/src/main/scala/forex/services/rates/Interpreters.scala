package forex.services.rates

import cats.effect.{Concurrent, Timer}
import cats.Applicative
import org.http4s.client.Client
import interpreters._

object Interpreters {
  def dummy[F[_]: Applicative]: Algebra[F] = new OneFrameDummy[F]()

  // def live[F[_]: Applicative]: Algebra[F] = dummy[F]  // stub
  def live[F[_]: Concurrent: Timer](client: Client[F]): Algebra[F] =
  new OneFrameLive[F](client)
}
