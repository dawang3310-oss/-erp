create table md_brand (
  id char(26) not null primary key,
  name varchar(128) not null,
  status varchar(16) not null default 'ACTIVE',
  created_at timestamp(6) not null default current_timestamp(6),
  constraint uk_md_brand_name unique (name)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_category (
  id char(26) not null primary key,
  parent_id char(26) null,
  name varchar(128) not null,
  path varchar(1024) not null,
  status varchar(16) not null default 'ACTIVE',
  created_at timestamp(6) not null default current_timestamp(6),
  constraint uk_md_category_parent_name unique (parent_id, name),
  constraint fk_md_category_parent foreign key (parent_id) references md_category(id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_spu (
  id char(26) not null primary key,
  spu_code varchar(64) not null,
  name varchar(256) not null,
  brand_id char(26) null,
  category_id char(26) null,
  attributes json null,
  status varchar(16) not null default 'DRAFT',
  version bigint not null default 0,
  created_at timestamp(6) not null default current_timestamp(6),
  updated_at timestamp(6) not null default current_timestamp(6) on update current_timestamp(6),
  constraint uk_md_spu_code unique (spu_code),
  constraint fk_md_spu_brand foreign key (brand_id) references md_brand(id),
  constraint fk_md_spu_category foreign key (category_id) references md_category(id),
  index ix_md_spu_status_updated (status, updated_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

alter table md_sku
  add column spu_id char(26) null after id,
  add column name varchar(256) null after sku_code,
  add column specifications json null after barcode,
  add column unit varchar(16) not null default '件' after specifications,
  add column version bigint not null default 0 after status;

insert into md_spu (id, spu_code, name, status)
select id, concat('LEGACY-', id), sku_code, status
from md_sku;

update md_sku
set spu_id = id,
    name = sku_code
where spu_id is null;

alter table md_sku
  modify column spu_id char(26) not null,
  modify column name varchar(256) not null,
  add constraint fk_md_sku_spu foreign key (spu_id) references md_spu(id),
  add index ix_md_sku_spu_status (spu_id, status);

create table md_product_image (
  id char(26) not null primary key,
  spu_id char(26) not null,
  object_key varchar(512) not null,
  source_url varchar(1024) null,
  content_sha256 char(64) not null,
  media_type varchar(64) not null,
  display_order int not null default 0,
  created_at timestamp(6) not null default current_timestamp(6),
  constraint uk_md_product_image_object unique (object_key),
  constraint fk_md_product_image_spu foreign key (spu_id) references md_spu(id),
  index ix_md_product_image_spu_order (spu_id, display_order)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_import_job (
  id char(26) not null primary key,
  filename varchar(256) not null,
  object_key varchar(512) not null,
  error_object_key varchar(512) null,
  status varchar(32) not null,
  create_count int not null default 0,
  update_count int not null default 0,
  skip_count int not null default 0,
  conflict_count int not null default 0,
  failure_count int not null default 0,
  confirmation_key varchar(128) null,
  created_by varchar(128) not null,
  created_at timestamp(6) not null default current_timestamp(6),
  started_at timestamp(6) null,
  finished_at timestamp(6) null,
  constraint uk_md_import_confirmation unique (confirmation_key),
  index ix_md_import_status_created (status, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_import_error (
  id char(26) not null primary key,
  import_job_id char(26) not null,
  source_row_number int not null,
  error_code varchar(64) not null,
  message varchar(512) not null,
  constraint fk_md_import_error_job foreign key (import_job_id) references md_import_job(id),
  index ix_md_import_error_job_row (import_job_id, source_row_number)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table md_export_job (
  id char(26) not null primary key,
  filter_json json not null,
  object_key varchar(512) null,
  status varchar(32) not null,
  idempotency_key varchar(128) not null,
  created_by varchar(128) not null,
  created_at timestamp(6) not null default current_timestamp(6),
  finished_at timestamp(6) null,
  constraint uk_md_export_idempotency unique (idempotency_key),
  index ix_md_export_status_created (status, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table audit_log (
  id char(26) not null primary key,
  aggregate_type varchar(64) not null,
  aggregate_id char(26) not null,
  action varchar(64) not null,
  actor varchar(128) not null,
  reason varchar(512) not null,
  before_json json null,
  after_json json null,
  created_at timestamp(6) not null default current_timestamp(6),
  index ix_audit_aggregate_time (aggregate_type, aggregate_id, created_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
