# Forex Proxy Service

A local proxy for currency exchange rates that fetches from the Paidy One-Frame service while respecting the 1000 requests/day token limit.

## Features Implemented

- Returns exchange rate for any supported currency pair (`/rates?from=USD&to=EUR`)
- Rates are guaranteed ≤ 5 minutes old
- Supports >> 10,000 requests/day using in-memory caching
- Single API call per refresh for all 72 pairs (9 currencies × 8 pairs)
- Refresh every 4 minutes (~720 external calls/day < 1000 limit)
- Descriptive JSON errors for stale/missing/unsupported rates
- Token loaded from `.env` (not committed)
- Background refresh using fs2 Stream + Cats Effect

## Quick Start

### Prerequisites

- Docker
- sbt 1.8+
- Java 18+

### 1. Start One-Frame service

```
docker run -p 8080:8080 paidyinc/one-frame
```

### 2. Configure token

Create .env in project root:

```
ONE_FRAME_TOKEN=10dc303535874aeccc86a8251e6992f5
```

### 3. Run the app

```
sbt run
```

Server starts on http://localhost:8081 (configurable in application.conf)

### 4. Test

Manual:

```
curl "http://localhost:8081/rates?from=USD&to=EUR"
```

Stress test (10,000 requests, concurrency 10):

```
ab -n 10000 -c 10 "http://localhost:8081/rates?from=USD&to=EUR"
```

- Mean time per request: 2.25 ms
- 99th percentile: 18 ms
- Longest request: 165 ms
- All requests successful from cache

## API

```
GET /rates?from=<currency>&to=<currency>
```

Supported currencies: AUD, CAD, CHF, EUR, GBP, NZD, JPY, SGD, USD

Response example:

```
{
  "from": "USD",
  "to": "EUR",
  "price": 0.9123,
  "timestamp": "2026-01-21T17:00:29.724Z"
}
```

Error example:

```
{"error": "Rate for USDEUR is stale"}
```

## Architecture & Design Choices

- Tagless Final style with Algebra[F[_]]
- Live interpreter (OneFrameLive) with in-memory Ref-based cache
- fs2 Stream for periodic background refresh (every 3 minutes)
- Single API call for all 72 pairs → ~480 calls/day (well under 1000 limit)
- Freshness check in get → returns descriptive error if stale
- Token loaded from .env (production-ready)

## Assumptions & Simplifications

- OneFrame Docker is running locally on port 8080
- Token is provided via .env (no fallback in production)
- All supported currencies are hardcoded (from Currency.scala)
- No persistence (in-memory cache only)
- No authentication on our API
- No rate limiting on our own service
- Used Ref.unsafe for simplicity in bootstrap

## Requirements Coverage

✅ Returns rate for supported pair

✅ Rate never older than 5 minutes

✅ Supports ≥ 10,000 requests/day with 1 token (cache hit rate ≈ 100% after initial refresh)

## Potential Improvements

- Redis-backed cache with TTL
- On-demand refresh with rate limiting
- Circuit breaker for OneFrame failures
- Prometheus metrics
- JSON error responses
- Health check endpoint
