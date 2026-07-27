create table iam_user (
  id char(26) not null primary key,
  username varchar(64) not null,
  password_hash varchar(255) not null,
  status varchar(16) not null default 'ACTIVE',
  created_at timestamp(6) not null default current_timestamp(6),
  constraint uk_iam_user_username unique (username)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table iam_role (
  id char(26) not null primary key,
  role_code varchar(64) not null,
  name varchar(128) not null,
  constraint uk_iam_role_code unique (role_code)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table iam_user_role (
  user_id char(26) not null,
  role_id char(26) not null,
  primary key (user_id, role_id),
  constraint fk_iam_user_role_user foreign key (user_id) references iam_user (id),
  constraint fk_iam_user_role_role foreign key (role_id) references iam_role (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table iam_user_shop_scope (
  user_id char(26) not null,
  shop_id char(26) not null,
  primary key (user_id, shop_id),
  constraint fk_iam_shop_scope_user foreign key (user_id) references iam_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;

create table iam_user_warehouse_scope (
  user_id char(26) not null,
  warehouse_id char(26) not null,
  primary key (user_id, warehouse_id),
  constraint fk_iam_warehouse_scope_user foreign key (user_id) references iam_user (id)
) engine=InnoDB default charset=utf8mb4 collate=utf8mb4_0900_ai_ci;
