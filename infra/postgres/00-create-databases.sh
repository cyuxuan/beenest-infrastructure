#!/bin/bash
# 初始化所有微服务数据库和表结构
#
# 数据库布局：
#   beenest          → 共享数据库，通过 schema 隔离各微服务
#     drone_system   → 核心业务服务（drone-system）
#     beenest_cas    → CAS Server（Apereo CAS 7.3.x）
#     beenest_payment → 支付微服务
#
# SQL 文件挂载在 /tmp/sql/ 下，不会被 docker-entrypoint 自动执行

# 1. 创建共享数据库 beenest
psql -v ON_ERROR_STOP=0 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE beenest;
EOSQL

# 2. 在 beenest 数据库中创建 schema
echo ">>> Creating schemas in beenest..."
psql -v ON_ERROR_STOP=0 --username "$POSTGRES_USER" --dbname "beenest" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS drone_system;
    CREATE SCHEMA IF NOT EXISTS beenest_cas;
    CREATE SCHEMA IF NOT EXISTS beenest_payment;
EOSQL

echo ">>> Initializing drone_system schema (PostGIS features may be skipped)..."
psql -v ON_ERROR_STOP=0 --username "$POSTGRES_USER" --dbname "beenest" -f /tmp/sql/01-schema-drone-system.sql
echo ">>> drone_system done."

echo ">>> Initializing beenest_payment schema..."
psql -v ON_ERROR_STOP=0 --username "$POSTGRES_USER" --dbname "beenest" -f /tmp/sql/02-schema-beenest-payment.sql
echo ">>> beenest_payment done."

echo ">>> Initializing beenest_cas schema..."
psql -v ON_ERROR_STOP=0 --username "$POSTGRES_USER" --dbname "beenest" -f /tmp/sql/03-schema-beenest-cas.sql
echo ">>> beenest_cas done."

echo ">>> All schemas initialized (check logs above for any skipped statements)."
exit 0
