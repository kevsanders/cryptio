-- Kraken transactions schema (H2)

-- Main transactions table
CREATE TABLE IF NOT EXISTS kraken_tx (
  id          VARCHAR(36)  PRIMARY KEY,
  ts          TIMESTAMP WITH TIME ZONE NOT NULL,   -- trade/ledger timestamp (UTC)
  pair        VARCHAR(24)  NOT NULL,               -- e.g. "ETH/EUR"
  base        VARCHAR(16)  NOT NULL,               -- e.g. "ETH"
  quote       VARCHAR(16)  NOT NULL,               -- e.g. "EUR"
  type        VARCHAR(16)  NOT NULL,               -- enum stored as text: buy/sell/deposit/withdrawal/staking/fee
  status      VARCHAR(16)  NOT NULL,               -- enum stored as text: NEW/PENDING/RECONCILED/ERROR

  price       DECIMAL(38,18),
  amount      DECIMAL(38,18),   -- base amount
  total       DECIMAL(38,18),   -- quote total
  fee         DECIMAL(38,18),   -- quote fee

  txid        VARCHAR(64),      -- exchange transaction id (may be null); unique when present
  order_id    VARCHAR(64),
  trade_id    VARCHAR(64),
  account_id  VARCHAR(64),

  notes       VARCHAR(2048),

  version     BIGINT NOT NULL DEFAULT 0,
  created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Uniqueness on txid (allows multiple NULLs, per SQL semantics)
CREATE UNIQUE INDEX IF NOT EXISTS uc_krakentx_txid ON kraken_tx (txid);

-- Helpful indexes for filtering
CREATE INDEX IF NOT EXISTS idx_krakentx_ts     ON kraken_tx (ts);
CREATE INDEX IF NOT EXISTS idx_krakentx_pair   ON kraken_tx (pair);
CREATE INDEX IF NOT EXISTS idx_krakentx_type   ON kraken_tx (type);
CREATE INDEX IF NOT EXISTS idx_krakentx_status ON kraken_tx (status);

-- Tags element collection
CREATE TABLE IF NOT EXISTS kraken_tx_tag (
  tx_id VARCHAR(36) NOT NULL,
  tag   VARCHAR(40) NOT NULL,
  CONSTRAINT pk_kraken_tx_tag PRIMARY KEY (tx_id, tag),
  CONSTRAINT fk_kraken_tx_tag__tx
    FOREIGN KEY (tx_id) REFERENCES kraken_tx(id)
    ON DELETE CASCADE
);

-- Optional: index on tag lookups (useful if you filter by tag)
CREATE INDEX IF NOT EXISTS idx_krakentx_tag_tag ON kraken_tx_tag (tag);
