package forex.services.rates.interpreters

import cats.effect.{Concurrent, Resource, Timer}
import cats.implicits._
import fs2.Stream
import org.http4s.client.Client
import org.http4s.{Header, Method, Request, Uri}
import org.http4s.circe.CirceEntityDecoder._
import io.circe.generic.auto._
import forex.domain.{Currency, Rate, Timestamp}
import forex.services.rates.{Algebra, errors}
import forex.services.rates.errors.Error

class OneFrameLive[F[_]: Concurrent: Timer](client: Client[F]) extends Algebra[F] {

  private val oneFrameUri = Uri.unsafeFromString("http://localhost:8080/rates")
  private val token = "10dc303535874aeccc86a8251e6992f5"
  private val supportedCurrencies: List[Currency] = List(Currency.USD, Currency.EUR, Currency.JPY /* add more */)
  private val allPairs: List[Rate.Pair] = supportedCurrencies.flatMap(from => supportedCurrencies.filter(_ != from).map(to => Rate.Pair(from, to)))
  private val batchSize = 50  // Adjust based on testing

  private val cache: Ref[F, Map[Rate.Pair, Rate]] = Ref.unsafe(Map.empty)

  // Background refresh stream (starts when service initializes)
  def refreshStream: Stream[F, Unit] = {
    Stream.awakeEvery[F](4.minutes) >>  // Refresh every 4 min
      Stream.emits(allPairs.grouped(batchSize).toList).evalMap { batch =>
        fetchBatch(batch).flatMap { rates =>
          cache.update { oldCache =>
            oldCache ++ rates.map(rate => rate.pair -> rate)
          }
        }
      }.drain
  }

  // Fetch a batch of pairs in one request
  private def fetchBatch(pairs: List[Rate.Pair]): F[List[Rate]] = {
    val queryParams = pairs.map(p => ("pair", s"${p.from}${p.to}"))
    val req = Request[F](Method.GET, oneFrameUri.withMultiQueryParams(queryParams))
      .putHeaders(Header.Raw(ci"token", token))  // ci = case-insensitive

    client.expect[List[OneFrameResponse]](req).map { responses =>
      responses.map { resp =>
        Rate(
          Rate.Pair(Currency.fromString(resp.from), Currency.fromString(resp.to)),
          Price(BigDecimal(resp.price)),
          Timestamp.from(resp.time_stamp)  // Parse ISO string to Timestamp
        )
      }
    }.handleErrorWith { e =>
      // Log error, return empty list or propagate
      Concurrent[F].pure(List.empty)
    }
  }

  // Algebra implementation: Serve from cache if fresh
  override def get(pair: Rate.Pair): F[Error Either Rate] = {
    if (!allPairs.contains(pair)) {
      Error.RateLookupFailed("Unsupported currency pair").asLeft.pure[F]
    } else {
      cache.get.map { currentCache =>
        currentCache.get(pair) match {
          case Some(rate) if Timestamp.now.diff(rate.timestamp) < 5.minutes =>
            rate.asRight
          case _ =>
            Error.RateLookupFailed("Rate stale or unavailable").asLeft
        }
      }
    }
  }

}

// Helper case class for One-Frame JSON
case class OneFrameResponse(from: String, to: String, bid: BigDecimal, ask: BigDecimal, price: BigDecimal, time_stamp: String)
