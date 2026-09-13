CREATE TABLE IF NOT EXISTS wallets (
  id UUID PRIMARY KEY,
  user_id VARCHAR(100) NOT NULL UNIQUE,
  upi_id VARCHAR(150),
  balance_paise BIGINT NOT NULL DEFAULT 0 CHECK (balance_paise >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Safe migration for wallets created before public UPI-style IDs were introduced.
ALTER TABLE wallets ADD COLUMN IF NOT EXISTS upi_id VARCHAR(150);
UPDATE wallets SET upi_id = user_id || '@wallet' WHERE upi_id IS NULL;
ALTER TABLE wallets ALTER COLUMN upi_id SET NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS wallets_upi_id_unique ON wallets(upi_id);
CREATE UNIQUE INDEX IF NOT EXISTS wallets_upi_id_ci_unique ON wallets(lower(upi_id));

-- One active session can claim a wallet. The primary key makes concurrent claims exclusive.
CREATE TABLE IF NOT EXISTS wallet_sessions (
  wallet_id UUID PRIMARY KEY REFERENCES wallets(id) ON DELETE CASCADE,
  session_id UUID NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS transfers (
  id UUID PRIMARY KEY,
  idempotency_key VARCHAR(255) NOT NULL UNIQUE,
  from_wallet_id UUID NOT NULL REFERENCES wallets(id),
  to_wallet_id UUID NOT NULL REFERENCES wallets(id),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS wallet_credits (
  id UUID PRIMARY KEY,
  wallet_id UUID NOT NULL REFERENCES wallets(id),
  amount_paise BIGINT NOT NULL CHECK (amount_paise > 0),
  idempotency_key VARCHAR(255) NOT NULL UNIQUE,
  status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'COMPLETED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at TIMESTAMPTZ
);
