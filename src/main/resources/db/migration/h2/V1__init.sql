-- H2 V1__init.sql

-- 1) exchange
create table if not exists exchange (
    id          int generated always as identity primary key,
    code        varchar(32) not null unique,
    name        varchar(128) not null
);

insert into exchange(code, name) values ('binance','Binance');
insert into exchange(code, name) values ('kraken','Kraken');

-- 2) exchange_account
create table if not exists exchange_account (
    id              bigint generated always as identity primary key,
    exchange_id     int not null,
    account_ref     varchar(128) not null,
    display_name    varchar(128),
    created_at      timestamp not null default current_timestamp,
    unique (exchange_id, account_ref),
    foreign key (exchange_id) references exchange(id) on delete cascade
);

-- 3) asset
create table if not exists asset (
    id              bigint generated always as identity primary key,
    symbol          varchar(32)  not null,
    chain           varchar(64)  not null default '',    -- normalize NULL -> ''
    contract        varchar(128) not null default '',    -- normalize NULL -> ''
    decimals        int not null default 18,             -- <-- was 'precision'
    constraint uq_asset unique (symbol, chain, contract) -- no expressions here
);


-- 3a) asset_alias
create table if not exists asset_alias (
    id              bigint generated always as identity primary key,
    exchange_id     int    not null,
    alias           varchar(64) not null,                -- store lowercase in code
    asset_id        bigint not null,
    unique (exchange_id, alias),
    foreign key (exchange_id) references exchange(id) on delete cascade,
    foreign key (asset_id)    references asset(id)    on delete restrict
);

-- 4) balance_ingest_log (UUID as CHAR(36), JSON as CLOB)
create table if not exists balance_ingest_log (
    id                  char(36) default random_uuid() primary key,  -- DEFAULT before PRIMARY KEY
    exchange_account_id bigint not null,
    as_of               timestamp not null,
    source              varchar(64) not null,
    http_status         int,
    payload             clob,
    created_at          timestamp not null default current_timestamp,
    foreign key (exchange_account_id) references exchange_account(id) on delete cascade
);
create index bil_idx on balance_ingest_log(exchange_account_id, as_of desc);

-- 5) balance_snapshot (compute total in queries)
create table if not exists balance_snapshot (
    id                  bigint generated always as identity primary key,
    exchange_account_id bigint not null,
    asset_id            bigint not null,
    exchange_symbol     varchar(64) not null,
    free_amt            numeric(38,18) not null default 0,
    locked_amt          numeric(38,18) not null default 0,
    as_of               timestamp not null,
    ingest_id           char(36),
    created_at          timestamp not null default current_timestamp,
    constraint uq_balance_snapshot unique (exchange_account_id, asset_id, as_of),
    foreign key (exchange_account_id) references exchange_account(id) on delete cascade,
    foreign key (asset_id) references asset(id) on delete restrict,
    foreign key (ingest_id) references balance_ingest_log(id) on delete set null
);
create index bs_idx1 on balance_snapshot(exchange_account_id, as_of desc);
create index bs_idx2 on balance_snapshot(exchange_account_id, asset_id, as_of desc);

-- 6) "Latest" view (H2: regular view with window function)
create view v_latest_balance as
select e.code          as exchange,
       ea.account_ref  as account,
       a.symbol        as asset,
       bs.free_amt,
       bs.locked_amt,
       (bs.free_amt + bs.locked_amt) as total_amt,
       bs.as_of
from (
    select x.*
    from (
        select bs.*,
               row_number() over (
                 partition by exchange_account_id, asset_id
                 order by as_of desc, id desc
               ) rn
        from balance_snapshot bs
    ) x
    where x.rn = 1
) bs
join exchange_account ea on ea.id = bs.exchange_account_id
join exchange e           on e.id  = ea.exchange_id
join asset a              on a.id  = bs.asset_id;


create table if not exists holding (
    id        bigint generated always as identity primary key,
    asset     varchar(64)  not null,
    quantity  numeric(38,18) not null default 0,
    avg_cost  numeric(38,18) not null default 0,
    version   bigint not null default 0
);
create index holding_asset_idx on holding(asset);

create table if not exists tax_lot (
    id         bigint generated always as identity primary key,
    asset      varchar(64)    not null,          -- store symbol, e.g. 'BTC'
    qty_open   numeric(38,18) not null default 0,
    cost_basis numeric(38,18) not null default 0,
    opened_at  timestamp      not null
);

create index tax_lot_asset_idx on tax_lot(asset);
create index tax_lot_opened_idx on tax_lot(opened_at);

create table if not exists tx (
    id           bigint generated always as identity primary key,
    exchange     varchar(64)   not null,
    account_ref  varchar(64),
    base         varchar(32)   not null,
    quote        varchar(32)   not null,
    type         varchar(16)   not null,            -- Enum stored as STRING
    quantity     numeric(38,18) not null default 0,
    price        numeric(38,18),
    fee          numeric(38,18),
    fee_asset    varchar(32),
    ts           timestamp     not null,
    external_id  varchar(96),

    -- Prevent duplicates from same exchange feed if you have an external id
    constraint uq_tx_exchange_external unique (exchange, external_id)
);

create index tx_ts_idx          on tx(ts);
create index tx_exchange_acc_idx on tx(exchange, account_ref);
create index tx_pair_idx        on tx(base, quote);
