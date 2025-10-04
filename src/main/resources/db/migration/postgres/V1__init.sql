-- V1__init.sql

create extension if not exists "uuid-ossp";

-- 1) Exchange catalog (extensible)
create table exchange (
    id              serial primary key,
    code            varchar(32) not null unique,  -- 'binance','kraken', etc.
    name            varchar(128) not null
);

insert into exchange (code, name) values
    ('binance','Binance'),
    ('kraken','Kraken')
on conflict (code) do nothing;

-- 2) Exchange account (your API keys/subaccounts/etc.)
create table exchange_account (
    id              bigserial primary key,
    exchange_id     int not null references exchange(id) on delete cascade,
    -- free-form identifiers that help you distinguish keys: e.g. key label, subaccount, UID
    account_ref     varchar(128) not null,
    display_name    varchar(128),
    created_at      timestamptz not null default now(),
    unique (exchange_id, account_ref)
);

-- 3) Canonical asset registry (normalized)
create table asset (
    id          bigserial primary key,
    symbol      varchar(32) not null,      -- 'BTC','ETH','USDT', etc.
    chain       varchar(64),               -- optional: 'ethereum','bsc','polygon'
    contract    varchar(128),              -- optional: token contract address
    precision   int not null default 18,
    constraint uq_asset unique (symbol, coalesce(chain,''), coalesce(contract,''))
);

-- 3a) Exchange-specific aliases -> canonical asset
-- e.g., ('kraken','XXBT') -> asset_id (BTC)
create table asset_alias (
    id              bigserial primary key,
    exchange_id     int not null references exchange(id) on delete cascade,
    alias           varchar(64) not null,      -- e.g. 'XXBT','ZUSD'
    asset_id        bigint not null references asset(id) on delete restrict,
    unique (exchange_id, lower(alias))
);

-- 4) Raw payloads (optional but very useful for audit/debug)
create table balance_ingest_log (
    id              uuid primary key default uuid_generate_v4(),
    exchange_account_id bigint not null references exchange_account(id) on delete cascade,
    as_of           timestamptz not null,        -- when snapshot was taken (exchange server time or your ingest time)
    source          varchar(64) not null,        -- 'binance.account','kraken.balance'
    http_status     int,
    payload         jsonb,                        -- raw response
    created_at      timestamptz not null default now()
);
create index on balance_ingest_log (exchange_account_id, as_of desc);

-- 5) Normalized balance snapshots (history)
-- We keep both the raw exchange symbol and the normalized asset_id.
create table balance_snapshot (
    id                  bigserial primary key,
    exchange_account_id bigint not null references exchange_account(id) on delete cascade,
    asset_id            bigint not null references asset(id) on delete restrict,
    exchange_symbol     varchar(64) not null,     -- e.g. 'BTC', 'XXBT', 'USDT'
    free_amt            numeric(38,18) not null default 0,
    locked_amt          numeric(38,18) not null default 0,
    total_amt           numeric(38,18) generated always as (free_amt + locked_amt) stored,
    as_of               timestamptz not null,     -- snapshot time (ideally exchange server time or ingest time)
    ingest_id           uuid references balance_ingest_log(id) on delete set null,
    created_at          timestamptz not null default now(),
    -- idempotency: one snapshot per (account, asset, moment)
    constraint uq_balance_snapshot unique (exchange_account_id, asset_id, as_of)
);

create index on balance_snapshot (exchange_account_id, as_of desc);
create index on balance_snapshot (exchange_account_id, asset_id, as_of desc);

-- 6) Latest balances materialized view (fast portfolio queries)
create materialized view mv_latest_balance as
select distinct on (bs.exchange_account_id, bs.asset_id)
       bs.exchange_account_id,
       bs.asset_id,
       bs.exchange_symbol,
       bs.free_amt,
       bs.locked_amt,
       bs.total_amt,
       bs.as_of,
       bs.created_at
from balance_snapshot bs
order by bs.exchange_account_id, bs.asset_id, bs.as_of desc, bs.id desc;

create index on mv_latest_balance (exchange_account_id, asset_id);
create index on mv_latest_balance (exchange_account_id, as_of desc);

-- Helper view: join latest balances to human-readable names
create view v_latest_balance as
select e.code          as exchange,
       ea.account_ref  as account,
       a.symbol        as asset,
       lb.free_amt,
       lb.locked_amt,
       lb.total_amt,
       lb.as_of
from mv_latest_balance lb
join exchange_account ea on ea.id = lb.exchange_account_id
join exchange e           on e.id  = ea.exchange_id
join asset a              on a.id  = lb.asset_id;

-- Optional: function to refresh MV (call from app/cron)
create or replace function refresh_latest_balance_mv()
returns void language sql as
$$
    refresh materialized view concurrently mv_latest_balance;
$$;

