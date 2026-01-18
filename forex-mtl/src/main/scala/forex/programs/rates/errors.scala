package forex.programs.rates

import forex.services.rates.errors.{ Error => ServiceError }  // Fixed import (use Error => ServiceError)

object errors {

  sealed trait Error
  object Error {
    case class RateLookupFailed(msg: String) extends Error  // Add this if not already there
  }

  def toProgramError(error: ServiceError): Error = error match {
    case ServiceError.RateLookupFailed(msg) => Error.RateLookupFailed(msg)  // Fixed case (use RateLookupFailed, not OneFrameLookupFailed)
  }

}