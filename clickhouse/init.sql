CREATE DATABASE IF NOT EXISTS shop_analytics;

-- =====================================================================
-- Kafka source tables (consume Debezium topics with unwrap SMT)
-- Each row arrives as a flat JSON document with __deleted flag.
-- =====================================================================

CREATE TABLE shop_analytics.kafka_customers
(
    id         Int64,
    name       String,
    email      String,
    city       String,
    created_at String,
    updated_at String,
    __deleted  String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:29092',
    kafka_topic_list = 'shop.public.customers',
    kafka_group_name = 'clickhouse-customers',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1,
    kafka_skip_broken_messages = 100;

CREATE TABLE shop_analytics.kafka_products
(
    id         Int64,
    name       String,
    category   String,
    price      Float64,
    stock      Int32,
    created_at String,
    updated_at String,
    __deleted  String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:29092',
    kafka_topic_list = 'shop.public.products',
    kafka_group_name = 'clickhouse-products',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1,
    kafka_skip_broken_messages = 100;

CREATE TABLE shop_analytics.kafka_orders
(
    id           Int64,
    customer_id  Int64,
    status       String,
    total_amount Float64,
    created_at   String,
    updated_at   String,
    __deleted    String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:29092',
    kafka_topic_list = 'shop.public.orders',
    kafka_group_name = 'clickhouse-orders',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1,
    kafka_skip_broken_messages = 100;

CREATE TABLE shop_analytics.kafka_order_items
(
    id         Int64,
    order_id   Int64,
    product_id Int64,
    quantity   Int32,
    unit_price Float64,
    __deleted  String
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:29092',
    kafka_topic_list = 'shop.public.order_items',
    kafka_group_name = 'clickhouse-order-items',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1,
    kafka_skip_broken_messages = 100;

-- =====================================================================
-- Target tables (ReplacingMergeTree handles updates; is_deleted flags rows)
-- =====================================================================

CREATE TABLE shop_analytics.customers
(
    id         Int64,
    name       String,
    email      String,
    city       String,
    created_at DateTime64(3),
    updated_at DateTime64(3),
    is_deleted UInt8
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY id;

CREATE TABLE shop_analytics.products
(
    id         Int64,
    name       String,
    category   String,
    price      Decimal(12, 2),
    stock      Int32,
    created_at DateTime64(3),
    updated_at DateTime64(3),
    is_deleted UInt8
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY id;

CREATE TABLE shop_analytics.orders
(
    id           Int64,
    customer_id  Int64,
    status       String,
    total_amount Decimal(14, 2),
    created_at   DateTime64(3),
    updated_at   DateTime64(3),
    is_deleted   UInt8
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY (created_at, id);

CREATE TABLE shop_analytics.order_items
(
    id         Int64,
    order_id   Int64,
    product_id Int64,
    quantity   Int32,
    unit_price Decimal(12, 2),
    is_deleted UInt8
)
ENGINE = ReplacingMergeTree
ORDER BY (order_id, id);

-- =====================================================================
-- Order events history: SEMUA versi status disimpan (tanpa dedup).
-- ReplacingMergeTree di tabel `orders` dedupe per (created_at, id) jadi
-- history status hilang setelah merge — tidak cocok untuk windowFunnel.
-- Tabel ini ORDER BY (id, updated_at) supaya tiap event row unik.
-- =====================================================================

CREATE TABLE shop_analytics.orders_events
(
    id           Int64,
    customer_id  Int64,
    status       String,
    total_amount Decimal(14, 2),
    created_at   DateTime64(3),
    updated_at   DateTime64(3),
    is_deleted   UInt8
)
ENGINE = MergeTree
ORDER BY (id, updated_at);

-- =====================================================================
-- Pre-aggregation showcase: AggregatingMergeTree dengan MV-on-MV chain.
-- Tiap minute bucket menyimpan state (count/sum/quantile) yang bisa
-- di-merge ulang lewat fungsi *Merge. Dashboard query baca dari sini
-- jauh lebih cepat daripada scan ulang orders_events tiap kali.
-- =====================================================================

CREATE TABLE shop_analytics.orders_per_minute
(
    minute       DateTime,
    events_count AggregateFunction(count),
    revenue_sum  AggregateFunction(sum, Decimal(14, 2)),
    p95_amount   AggregateFunction(quantile(0.95), Float64)
)
ENGINE = AggregatingMergeTree
ORDER BY minute;

-- =====================================================================
-- Materialized views move messages from Kafka tables to target tables.
-- =====================================================================

CREATE MATERIALIZED VIEW shop_analytics.mv_customers TO shop_analytics.customers AS
SELECT
    id,
    name,
    email,
    city,
    parseDateTime64BestEffortOrZero(created_at, 3) AS created_at,
    parseDateTime64BestEffortOrZero(updated_at, 3) AS updated_at,
    toUInt8(__deleted = 'true')          AS is_deleted
FROM shop_analytics.kafka_customers;

CREATE MATERIALIZED VIEW shop_analytics.mv_products TO shop_analytics.products AS
SELECT
    id,
    name,
    category,
    toDecimal64(price, 2)                AS price,
    stock,
    parseDateTime64BestEffortOrZero(created_at, 3) AS created_at,
    parseDateTime64BestEffortOrZero(updated_at, 3) AS updated_at,
    toUInt8(__deleted = 'true')          AS is_deleted
FROM shop_analytics.kafka_products;

CREATE MATERIALIZED VIEW shop_analytics.mv_orders TO shop_analytics.orders AS
SELECT
    id,
    customer_id,
    status,
    toDecimal64(total_amount, 2)         AS total_amount,
    parseDateTime64BestEffortOrZero(created_at, 3) AS created_at,
    parseDateTime64BestEffortOrZero(updated_at, 3) AS updated_at,
    toUInt8(__deleted = 'true')          AS is_deleted
FROM shop_analytics.kafka_orders;

CREATE MATERIALIZED VIEW shop_analytics.mv_order_items TO shop_analytics.order_items AS
SELECT
    id,
    order_id,
    product_id,
    quantity,
    toDecimal64(unit_price, 2)  AS unit_price,
    toUInt8(__deleted = 'true') AS is_deleted
FROM shop_analytics.kafka_order_items;

-- MV kedua yang membaca kafka_orders dan mengisi orders_events (full history).
-- Dua MV pada Kafka engine yang sama → ClickHouse fanout, masing-masing dapat
-- semua event. Cocok untuk pattern "current state + event history sekaligus".
CREATE MATERIALIZED VIEW shop_analytics.mv_orders_events TO shop_analytics.orders_events AS
SELECT
    id,
    customer_id,
    status,
    toDecimal64(total_amount, 2)                  AS total_amount,
    parseDateTime64BestEffortOrZero(created_at, 3) AS created_at,
    parseDateTime64BestEffortOrZero(updated_at, 3) AS updated_at,
    toUInt8(__deleted = 'true')                   AS is_deleted
FROM shop_analytics.kafka_orders;

-- MV-on-MV: setiap INSERT ke orders_events trigger MV ini, yang lalu
-- agregat ke orders_per_minute. ClickHouse mendukung chain MV begini.
CREATE MATERIALIZED VIEW shop_analytics.mv_orders_per_minute TO shop_analytics.orders_per_minute AS
SELECT
    toStartOfMinute(created_at)                  AS minute,
    countState()                                  AS events_count,
    sumState(total_amount)                        AS revenue_sum,
    quantileState(0.95)(toFloat64(total_amount))  AS p95_amount
FROM shop_analytics.orders_events
GROUP BY minute;
