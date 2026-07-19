create table ord_sales_order (
  id char(26) not null primary key,
  platform varchar(32) not null,
  shop_id char(26) not null,
  platform_order_id varchar(128) not null,
  status varchar(32) not null,
  paid_at timestamp(6) not null,
  receiver_ciphertext text not null,
  version bigint not null default 0,
  created_at timestamp(6) not null default current_timestamp(6),
  updated_at timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
  constraint uk_ord_sales_order_identity unique (platform, shop_id, platform_order_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table ord_sales_order_line (
  id char(26) not null primary key,
  order_id char(26) not null,
  platform_sku_id varchar(128) not null,
  internal_sku_code varchar(64) null,
  quantity int not null,
  paid_amount_fen bigint not null,
  constraint ck_ord_line_quantity check (quantity > 0),
  constraint ck_ord_line_amount check (paid_amount_fen >= 0),
  constraint fk_ord_line_order foreign key (order_id) references ord_sales_order (id),
  index ix_ord_line_order (order_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table ord_exception (
  id char(26) not null primary key,
  order_id char(26) not null,
  error_code varchar(64) not null,
  detail varchar(512) not null,
  status varchar(32) not null,
  created_at timestamp(6) not null default current_timestamp(6),
  constraint fk_ord_exception_order foreign key (order_id) references ord_sales_order (id),
  index ix_ord_exception_order_status (order_id, status)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
