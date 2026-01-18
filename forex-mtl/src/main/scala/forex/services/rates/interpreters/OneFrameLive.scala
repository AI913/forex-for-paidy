package forex.services.rates.interpreters

import cats.effect.{Concurrent, Timer, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.Stream
import org.http4s.client.Client
import org.http4s.circe._
import org.http4s.implicits._
import org.http4s.{Method, Request, Uri}
import io.circe.generic.auto._
import scala.concurrent.duration._
import forex.domain._
import forex.services.rates.{Algebra, errors => serviceErrors}

final class OneFrameLive[F[_]: Concurrent: Timer](client: Client[F]) extends Algebra[F] {

  private val baseUri = uri"http://localhost:8080/rates"
  private val token = "10dc303535874aeccc86a8251e6992f5"

  private val batchSize = 20

  private val currencies = List(
    Currency.AUD, Currency.CAD, Currency.CHF,
    Currency.EUR, Currency.GBP, Currency.NZD,
    Currency.JPY, Currency.SGD, Currency.USD
  )

  private val allPairs: List[Rate.Pair] =
    for {
      from <- currencies
      to   <- currencies if from != to
    } yield Rate.Pair(from, to)

  private val cache = Ref.unsafe[F, Map[Rate.Pair, Rate]](Map.empty)

  val refreshStream = Stream.awakeEvery[F](3.minutes).evalMap { _ =>
    refreshAll
  }.drain

  def refreshAll: F[Unit] = {
    Sync[F].delay(println("Starting refresh for " + allPairs.size + " pairs...")).flatMap { _ =>
      allPairs.grouped(batchSize).toList.traverse { batch =>
        Sync[F].delay(println("Fetching batch of " + batch.size + " pairs...")).flatMap { _ =>
          val queryUri = batch.foldLeft(baseUri) { (u, p) =>
            u.withQueryParam("pair", s"${p.from.show}${p.to.show}")
          }

          val request = Request[F](Method.GET, queryUri).putHeaders("token" -> token)

          implicit val decoder = jsonOf[F, List[OneFrameResponse]]

          client.expect[List[OneFrameResponse]](request).map { responses =>
            println(s"Fetched ${responses.size} rates in batch")
            responses.map { r =>
              val pair = Rate.Pair(Currency.fromString(r.from), Currency.fromString(r.to))
              pair -> Rate(pair, Price(r.price), Timestamp.unsafeFrom(r.time_stamp))
            }.toMap
          }.handleErrorWith { e =>
            Sync[F].delay(println(s"Batch fetch failed: ${e.getMessage}")).as(Map.empty)
          }
        }
      }.map { batchMaps =>
        val allNewMap = batchMaps.flatten.toMap
        println(s"Total fetched and cached ${allNewMap.size} rates")
        allNewMap
      }.flatMap(cache.set)
    }.handleErrorWith { e =>
      Sync[F].delay(println(s"Full refresh failed: ${e.getMessage}"))
    }
  }

  override def get(pair: Rate.Pair): F[Either[serviceErrors.Error, Rate]] =cache.get.map { map =>
    map.get(pair) match {
      case Some(rate) if rate.timestamp.isFresh(5.minutes) => Right(rate)
      case _ => Left(serviceErrors.Error.RateLookupFailed("Rate stale or not available"))
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