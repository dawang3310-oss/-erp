create table ful_order (
  id char(26) not null primary key,
  sales_order_id char(26) not null,
  warehouse_id char(26) not null,
  fulfillment_type varchar(32) not null,
  status varchar(32) not null,
  created_at timestamp(6) not null default current_timestamp(6),
  constraint uk_ful_order_sales_warehouse unique (sales_order_id, warehouse_id),
  index ix_ful_order_sales_order (sales_order_id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
