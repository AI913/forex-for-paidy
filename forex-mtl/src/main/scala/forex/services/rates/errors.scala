package forex.services.rates

object errors {

  sealed trait Error
  object Error {
    case class RateLookupFailed(msg: String) extends Error
  }

}