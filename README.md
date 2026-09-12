# Wallet transfer service

Run the full stack:

```sh
docker compose up --build
```

The Dockerfiles use BuildKit dependency caches. The first build downloads base images and
dependencies; later builds reuse those layers and only rebuild the changed application.

## Free public deployment (Render + Neon)

This repository includes `render.yaml` for a public demo deployment. It creates a free
Render API service and a free Render static site; use a free Neon PostgreSQL database for
durable data.

1. Push this repository to GitHub, then create a Neon database. In Neon, copy the JDBC URL
   in this form: `jdbc:postgresql://HOST/neondb?sslmode=require`, together with its user and
   password.
2. In Render, select **New → Blueprint** and choose this repository. Render reads
   `render.yaml` and asks for the required values.
3. Set `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`,
   `SPRING_DATASOURCE_PASSWORD`, and `WALLET_TOKENS`. Give `WALLET_TOKENS` unguessable
   tokens, for example `long-random-token-for-alice:alice,long-random-token-for-bob:bob`.
4. Deploy the API first. Copy its public URL, then set the static site's `VITE_API_URL` to
   that URL and redeploy the static site. Vite embeds this value at build time.
5. Open and share the static site's public Render URL.

The free API may sleep after inactivity, so its first request can take about a minute. This
is a demo only: anybody possessing a token can add balance, and it must not be used for real
money or sensitive data.

Open [http://localhost:3000](http://localhost:3000). The API runs at `http://localhost:8080` and uses PostgreSQL. Demo credentials are `alice-token` and `bob-token`; set `WALLET_TOKENS` in `docker-compose.yml` to replace them (`token:user-id` pairs separated by commas).

All monetary values are `amount_paise` integer values. A new wallet starts at zero, so seed funds in PostgreSQL for a demo transfer, for example:

```sh
docker compose exec postgres psql -U wallet -d wallet -c "UPDATE wallets SET balance_paise=100000 WHERE user_id='alice';"
```

## API

Every endpoint needs `Authorization: Bearer <token>`.

```sh
# Create or retrieve the caller's wallet
curl -X POST localhost:8080/wallets -H 'Authorization: Bearer alice-token'

# Transfer using public UPI-style IDs (for example, `alice@wallet`)
curl -X POST localhost:8080/transfers \
  -H 'Authorization: Bearer alice-token' -H 'Content-Type: application/json' \
  -d '{"from":"alice@wallet","to":"bob@wallet","amount_paise":500,"idempotency_key":"payment-001"}'
```

Add funds to the authenticated caller's wallet (use a distinct idempotency key for each new credit):

```sh
curl -X POST localhost:8080/wallets/alice%40wallet/credits \
  -H 'Authorization: Bearer alice-token' -H 'Content-Type: application/json' \
  -d '{"amount_paise":1000,"idempotency_key":"initial-funds-001"}'
```

Read that wallet's full credit and transfer history:

```sh
curl localhost:8080/wallets/alice%40wallet/transactions \
  -H 'Authorization: Bearer alice-token'
```

`POST /wallets` is get-or-create for the authenticated user. Wallet, credit, and history reads only permit the wallet owner. Transfers use an atomic conditional debit and credit in one SQL transaction, and persist the client idempotency key. Insufficient funds create a `REJECTED` transfer without changing balances.

Balance credits and transfers run at PostgreSQL `SERIALIZABLE` isolation. The service retries transient serialization or deadlock conflicts up to four times; if the wallet remains busy it returns `503`, and callers should retry with the **same** idempotency key.

## Public observability

Each request receives an `X-Correlation-Id` response header (or preserves a supplied header), and
application logs are emitted as structured JSON. Recent meaningful domain events are publicly viewable:

```sh
curl http://localhost:8080/logs
```

Prometheus-format metrics are public at:

```sh
curl http://localhost:8080/metrics
```

HTTP metrics include request counts, status-tagged errors, latency histograms, and a p99 series.
Domain counters include `wallet_transfers_created_total`,
`wallet_transfers_declined_insufficient_funds_total`, and `wallet_idempotent_replays_total`.

An asynchronous check runs every 10 seconds and publishes the balance invariant gauges:
`wallet_balance_total_paise`, `wallet_balance_expected_total_paise`,
`wallet_balance_delta_paise`, and `wallet_balance_consistent` (`1` is consistent; `0` is not).
Completed credits are the funding source and transfers preserve the total. Do not update wallet
balances directly in PostgreSQL, because that intentionally makes the check fail.

For an on-demand database check, run `scripts/check-balance-consistency.sql` with `psql`.

## Dummy data

After `docker compose up` is running, load one of the idempotent datasets (each wallet receives
`100000` paise and a matching completed credit record):

```sh
# Choose one dataset size.
docker compose exec -T postgres psql -U wallet -d wallet < scripts/seed-dummy-users-100.sql
docker compose exec -T postgres psql -U wallet -d wallet < scripts/seed-dummy-users-1000.sql
docker compose exec -T postgres psql -U wallet -d wallet < scripts/seed-dummy-users-10000.sql
```

The generated UPI IDs follow `seed100-user-1@wallet`, `seed1000-user-1@wallet`, or
`seed10000-user-1@wallet`. A script can be run repeatedly without duplicating balances.

## Concurrent wallet check

After the API is running, verify that concurrent get-or-create requests for one bearer-token user all return exactly one wallet:

```sh
./scripts/concurrent-wallet-check.sh alice-token 100
```

The optional final argument is the API base URL, for example `http://localhost:8080`.
