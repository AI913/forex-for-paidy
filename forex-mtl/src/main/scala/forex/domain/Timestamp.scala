package forex.domain

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import scala.concurrent.duration.FiniteDuration

case class Timestamp(value: OffsetDateTime) extends AnyVal {
  def isFresh(maxAge: FiniteDuration): Boolean = {
    val maxAgeNanos = maxAge.toNanos
    val now        = OffsetDateTime.now()
    val deadline   = value.plus(maxAgeNanos, ChronoUnit.NANOS)
    !now.isAfter(deadline)
  }
}

object Timestamp {
  def now: Timestamp =
    Timestamp(OffsetDateTime.now)

  def unsafeFrom(isoString: String): Timestamp =
    Timestamp(OffsetDateTime.parse(isoString))  // assumes ISO format
}

