create table if not exists ingest_checkpoint (
    id           bigint generated always as identity primary key,
    exchange     varchar(32)  not null,
    account_ref  varchar(128) not null,
    kind         varchar(32)  not null,
    cursor_str   varchar(128),
    cursor_ts    timestamp,
    updated_at   timestamp not null default current_timestamp,
    unique (exchange, account_ref, kind)
);

-- guarantees only one copy of a logical event from an exchange
create unique index if not exists uq_tx_exchange_external
on tx(exchange, external_id);
