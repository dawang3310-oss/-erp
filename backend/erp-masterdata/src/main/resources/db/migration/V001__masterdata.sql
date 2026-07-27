create table md_shop (
  id char(26) not null primary key,
  platform varchar(32) not null,
  shop_code varchar(64) not null,
  name varchar(128) not null,
  status varchar(16) not null default 'ACTIVE',
  created_at timestamp(6) not null default current_timestamp(6),
  updated_at timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
  constraint uk_md_shop_platform_code unique (platform, shop_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_warehouse (
  id char(26) not null primary key,
  warehouse_code varchar(64) not null,
  name varchar(128) not null,
  fulfillment_type varchar(32) not null,
  status varchar(16) not null default 'ACTIVE',
  created_at timestamp(6) not null default current_timestamp(6),
  updated_at timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
  constraint uk_md_warehouse_code unique (warehouse_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_sku (
  id char(26) not null primary key,
  sku_code varchar(64) not null,
  barcode varchar(64) null,
  batch_enabled boolean not null default false,
  serial_enabled boolean not null default false,
  status varchar(16) not null default 'ACTIVE',
  created_at timestamp(6) not null default current_timestamp(6),
  updated_at timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
  constraint uk_md_sku_code unique (sku_code),
  constraint uk_md_sku_barcode unique (barcode)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_channel_sku_mapping (
  id char(26) not null primary key,
  platform varchar(32) not null,
  shop_id char(26) not null,
  platform_sku_id varchar(128) not null,
  internal_sku_code varchar(64) not null,
  created_at timestamp(6) not null default current_timestamp(6),
  updated_at timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
  constraint uk_md_channel_sku unique (platform, shop_id, platform_sku_id),
  constraint fk_md_channel_sku_shop foreign key (shop_id) references md_shop (id),
  constraint fk_md_channel_sku_internal_sku foreign key (internal_sku_code) references md_sku (sku_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
