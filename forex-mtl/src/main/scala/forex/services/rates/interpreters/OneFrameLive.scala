package forex.services.rates.interpreters

import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.Stream
import org.http4s.client.Client
import org.http4s.{Method, Request, Query}
import org.http4s.circe._
import org.http4s.implicits._
import io.circe.generic.auto._
import scala.concurrent.duration._
import forex.domain._
import forex.services.rates.{Algebra, errors => serviceErrors}

final class OneFrameLive[F[_]: Concurrent: Timer](client: Client[F], token: String) extends Algebra[F] {

  private val baseUri = uri"http://localhost:8080/rates"

  private val currencies = List(
    Currency.AUD, Currency.CAD, Currency.CHF, Currency.EUR, 
    Currency.GBP, Currency.NZD, Currency.JPY, Currency.SGD, Currency.USD
  )

  private val allPairs = for {
    from <- currencies
    to <- currencies if from != to
  } yield Rate.Pair(from, to)

  private val cache = Ref.unsafe[F, Map[Rate.Pair, Rate]](Map.empty)

  // Refresh every 90 seconds (well under 5-minute requirement, stays under 1000 API calls/day)
  val refreshStream = Stream.awakeEvery[F](90.seconds).evalMap { _ =>
    refreshAll
  }.drain

  def refreshAll: F[Unit] = {
    Concurrent[F].delay(println(s"Refreshing all ${allPairs.size} pairs...")).flatMap { _ =>
      // Fetch ALL 72 pairs in a single request
      val pairParams = allPairs.map(p => ("pair", s"${p.from.show}${p.to.show}"))
      val queryUri = baseUri.copy(query = Query.fromPairs(pairParams: _*))
      val request = Request[F](Method.GET, queryUri).putHeaders("token" -> token)

      implicit val decoder: org.http4s.EntityDecoder[F, List[OneFrameResponse]] = 
        jsonOf[F, List[OneFrameResponse]]

      client.expect[List[OneFrameResponse]](request).flatMap { responses =>
        Concurrent[F].delay(println(s"Fetched ${responses.size} rates from One-Frame API")).flatMap { _ =>
          val newMap = responses.map { r =>
            val pair = Rate.Pair(Currency.fromString(r.from), Currency.fromString(r.to))
            pair -> Rate(pair, Price(r.price), Timestamp.unsafeFrom(r.time_stamp))
          }.toMap
          
          cache.set(newMap) >> 
          Concurrent[F].delay(println(s"Cache updated with ${newMap.size} rates"))
        }
      }.handleErrorWith { e =>
        Concurrent[F].delay(println(s"ERROR: Failed to fetch rates: ${e.getMessage}")) >>
        Concurrent[F].delay(e.printStackTrace())
      }
    }
  }

  override def get(pair: Rate.Pair): F[Either[serviceErrors.Error, Rate]] = {
    cache.get.map { map =>
      map.get(pair) match {
        case Some(rate) if rate.timestamp.isFresh(5.minutes) => 
          Right(rate)
        case Some(_) => 
          Left(serviceErrors.Error.RateLookupFailed(s"Rate for ${pair.from.show}${pair.to.show} is stale"))
        case None => 
          Left(serviceErrors.Error.RateLookupFailed(s"Rate for ${pair.from.show}${pair.to.show} not available"))
      }
    }
  }
}

private case class OneFrameResponse(
  from: String, 
  to: String, 
  bid: BigDecimal, 
  ask: BigDecimal, 
  price: BigDecimal, 
  time_stamp: String
)
