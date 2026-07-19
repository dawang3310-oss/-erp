create table ord_raw_order_version (
  id char(26) not null primary key,
  platform varchar(32) not null,
  shop_id char(26) not null,
  platform_order_id varchar(128) not null,
  payload_ref varchar(512) not null,
  payload_sha256 char(64) not null,
  received_at timestamp(6) not null default current_timestamp(6),
  constraint uk_ord_raw_order_payload unique (
    platform,
    shop_id,
    platform_order_id,
    payload_sha256
  ),
  index ix_ord_raw_order_identity (platform, shop_id, platform_order_id, received_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
