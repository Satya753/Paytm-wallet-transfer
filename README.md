# Wallet transfer service

Run the full stack:

```sh
docker compose up --build
```

The Dockerfiles use BuildKit dependency caches. The first build downloads base images and
dependencies; later builds reuse those layers and only rebuild the changed application.

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

## Concurrent wallet check

After the API is running, verify that concurrent get-or-create requests for one bearer-token user all return exactly one wallet:

```sh
./scripts/concurrent-wallet-check.sh alice-token 100
```

The optional final argument is the API base URL, for example `http://localhost:8080`.
