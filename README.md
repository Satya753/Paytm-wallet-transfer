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

# Transfer (wallet UUIDs shown as placeholders)
curl -X POST localhost:8080/transfers \
  -H 'Authorization: Bearer alice-token' -H 'Content-Type: application/json' \
  -d '{"from":"FROM_UUID","to":"TO_UUID","amount_paise":500,"idempotency_key":"payment-001"}'
```

`POST /wallets` is get-or-create for the authenticated user. Wallet and transfer reads only permit the transfer sender or wallet owner. Transfers lock both wallet rows in a stable order, debit and credit in one SQL transaction, and persist the client idempotency key. Insufficient funds create a `REJECTED` transfer without changing balances.

## Concurrent wallet check

After the API is running, verify that concurrent get-or-create requests for one bearer-token user all return exactly one wallet:

```sh
./scripts/concurrent-wallet-check.sh alice-token 100
```

The optional final argument is the API base URL, for example `http://localhost:8080`.
