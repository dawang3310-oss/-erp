create table sys_outbox (
  id varchar(26) not null primary key,
  event_type varchar(128) not null,
  aggregate_id varchar(64) not null,
  payload_json json not null,
  status varchar(16) not null,
  attempts int not null default 0,
  next_attempt_at timestamp(6) not null,
  created_at timestamp(6) not null,
  published_at timestamp(6) null,
  index ix_outbox_pending (status, next_attempt_at)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
