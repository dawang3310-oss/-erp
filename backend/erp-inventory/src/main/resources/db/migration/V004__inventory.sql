create table inv_balance (
  warehouse_id varchar(26) not null,
  sku_code varchar(64) not null,
  sellable_qty int not null default 0,
  reserved_qty int not null default 0,
  version bigint not null default 0,
  primary key (warehouse_id, sku_code),
  constraint ck_inv_balance_sellable check (sellable_qty >= 0),
  constraint ck_inv_balance_reserved check (reserved_qty >= 0)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table inv_ledger (
  id char(26) not null primary key,
  warehouse_id varchar(26) not null,
  sku_code varchar(64) not null,
  quantity_delta int not null,
  balance_type varchar(32) not null,
  reason_code varchar(32) not null,
  source_id varchar(64) not null,
  occurred_at timestamp(6) not null default current_timestamp(6),
  constraint uk_inv_ledger_source unique (reason_code, source_id, warehouse_id, sku_code),
  index ix_inv_ledger_stock_time (warehouse_id, sku_code, occurred_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table inv_reservation (
  id char(26) not null primary key,
  order_id varchar(26) not null,
  warehouse_id varchar(26) not null,
  sku_code varchar(64) not null,
  quantity int not null,
  status varchar(32) not null,
  created_at timestamp(6) not null default current_timestamp(6),
  constraint ck_inv_reservation_quantity check (quantity > 0),
  constraint uk_inv_reservation_order_stock unique (order_id, warehouse_id, sku_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
