# ClickHouse Query Cheatsheet

Kumpulan query siap copy-paste ke `http://localhost:8123/play`.

**Catatan:**
- Login: user `analytics`, password `analytics`.
- Semua query pakai prefix `shop_analytics.<tabel>` karena Play UI tidak punya field database.
- `FINAL` modifier diperlukan karena tabel pakai `ReplacingMergeTree(updated_at)` — versi terbaru per primary key dipilih saat query (kalau tanpa `FINAL`, kamu dapat semua versi termasuk yang sudah di-overwrite oleh UPDATE).
- Filter `is_deleted = 0` untuk hide row yang ter-DELETE di Postgres (Debezium kirim event `__deleted=true` jadi row baru di ReplacingMergeTree).

---

## 1. Verifikasi Pipeline

### Hitung row per tabel

```sql
SELECT 'customers'   AS tabel, count() AS rows FROM shop_analytics.customers   FINAL WHERE is_deleted = 0
UNION ALL SELECT 'products',    count() FROM shop_analytics.products    FINAL WHERE is_deleted = 0
UNION ALL SELECT 'orders',      count() FROM shop_analytics.orders      FINAL WHERE is_deleted = 0
UNION ALL SELECT 'order_items', count() FROM shop_analytics.order_items FINAL WHERE is_deleted = 0;
```

### Order paling baru (lihat freshness pipeline)

```sql
SELECT id, customer_id, status, total_amount, created_at, updated_at
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
ORDER BY created_at DESC
LIMIT 10;
```

### Lag CDC — selisih waktu antara created_at OLTP vs sekarang

```sql
SELECT
    id,
    created_at                                     AS oltp_created,
    now()                                          AS now_olap,
    dateDiff('second', created_at, now())          AS lag_seconds
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
ORDER BY created_at DESC
LIMIT 10;
```

---

## 2. Business Analytics

### Top 5 produk by revenue

```sql
SELECT
    p.name     AS product,
    p.category AS category,
    sum(oi.quantity)                  AS units_sold,
    sum(oi.quantity * oi.unit_price)  AS revenue
FROM shop_analytics.order_items AS oi FINAL
JOIN shop_analytics.products    AS p  FINAL ON p.id = oi.product_id
WHERE oi.is_deleted = 0 AND p.is_deleted = 0
GROUP BY p.name, p.category
ORDER BY revenue DESC
LIMIT 5;
```

### Revenue per kategori

```sql
SELECT
    p.category                        AS category,
    count(DISTINCT oi.order_id)       AS orders,
    sum(oi.quantity)                  AS units_sold,
    sum(oi.quantity * oi.unit_price)  AS revenue,
    round(avg(oi.quantity * oi.unit_price), 2) AS avg_line_value
FROM shop_analytics.order_items AS oi FINAL
JOIN shop_analytics.products    AS p  FINAL ON p.id = oi.product_id
WHERE oi.is_deleted = 0 AND p.is_deleted = 0
GROUP BY p.category
ORDER BY revenue DESC;
```

### Order count per status (state distribution)

```sql
SELECT status, count() AS jumlah
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
GROUP BY status
ORDER BY jumlah DESC;
```

### Revenue per kota — JOIN 3 tabel

```sql
SELECT
    c.city                            AS kota,
    count(DISTINCT o.id)              AS total_orders,
    sum(oi.quantity * oi.unit_price)  AS revenue
FROM shop_analytics.order_items AS oi FINAL
JOIN shop_analytics.orders     AS o FINAL ON o.id = oi.order_id
JOIN shop_analytics.customers  AS c FINAL ON c.id = o.customer_id
WHERE oi.is_deleted = 0 AND o.is_deleted = 0 AND c.is_deleted = 0
GROUP BY c.city
ORDER BY revenue DESC
LIMIT 10;
```

### Top 10 spender (customer lifetime value)

```sql
SELECT
    c.name                            AS customer,
    c.city                            AS kota,
    count(DISTINCT o.id)              AS total_orders,
    sum(oi.quantity * oi.unit_price)  AS lifetime_value
FROM shop_analytics.order_items AS oi FINAL
JOIN shop_analytics.orders     AS o FINAL ON o.id = oi.order_id
JOIN shop_analytics.customers  AS c FINAL ON c.id = o.customer_id
WHERE oi.is_deleted = 0 AND o.is_deleted = 0 AND c.is_deleted = 0
GROUP BY c.name, c.city
ORDER BY lifetime_value DESC
LIMIT 10;
```

### Average Order Value (AOV)

```sql
SELECT
    count()                           AS total_orders,
    sum(total_amount)                 AS total_revenue,
    round(avg(total_amount), 2)       AS avg_order_value,
    round(quantile(0.5)(total_amount), 2)  AS median_order_value,
    round(quantile(0.95)(total_amount), 2) AS p95_order_value
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0 AND status != 'PLACED';
```

---

## 3. Real-Time Monitoring (run berulang)

### Throughput order per 10 detik (re-run untuk lihat naik)

```sql
SELECT
    toStartOfInterval(created_at, INTERVAL 10 SECOND) AS bucket,
    count()                                            AS orders,
    sum(total_amount)                                  AS revenue
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
  AND created_at >= now() - INTERVAL 5 MINUTE
GROUP BY bucket
ORDER BY bucket DESC;
```

### Throughput per menit (jendela lebih panjang)

```sql
SELECT
    toStartOfMinute(created_at) AS minute,
    count()                     AS orders,
    sum(total_amount)           AS revenue
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
GROUP BY minute
ORDER BY minute DESC
LIMIT 30;
```

### Revenue running total (window function)

```sql
SELECT
    toStartOfMinute(created_at) AS minute,
    sum(total_amount)           AS revenue_per_min,
    sum(sum(total_amount)) OVER (ORDER BY toStartOfMinute(created_at))
                                AS cumulative_revenue
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
GROUP BY minute
ORDER BY minute;
```

---

## 4. Data Quality / CDC Health

### Berapa row yang sudah ter-DELETE di OLTP?

```sql
SELECT
    'customers'   AS tabel, sum(is_deleted) AS deleted_rows FROM shop_analytics.customers   FINAL
UNION ALL SELECT 'products',    sum(is_deleted) FROM shop_analytics.products    FINAL
UNION ALL SELECT 'orders',      sum(is_deleted) FROM shop_analytics.orders      FINAL
UNION ALL SELECT 'order_items', sum(is_deleted) FROM shop_analytics.order_items FINAL;
```

### Cek duplikasi karena ReplacingMergeTree belum merge

`FINAL` memforce dedup di query-time. Tanpa `FINAL`, count bisa lebih besar karena setiap UPDATE bikin row baru.

```sql
SELECT
    count()       AS total_versions,
    uniqExact(id) AS unique_orders,
    count() - uniqExact(id) AS pending_dedup
FROM shop_analytics.orders;
```

### Force merge agar dedup terjadi (manual compaction)

```sql
OPTIMIZE TABLE shop_analytics.orders FINAL;
OPTIMIZE TABLE shop_analytics.customers FINAL;
OPTIMIZE TABLE shop_analytics.products FINAL;
OPTIMIZE TABLE shop_analytics.order_items FINAL;
```

### Lihat lifecycle order — semua versi sebuah order ID

```sql
SELECT id, status, total_amount, updated_at, is_deleted
FROM shop_analytics.orders
WHERE id = 100   -- ganti ID-nya
ORDER BY updated_at;
```

---

## 5. Schema & System Inspection

### List semua tabel di database

```sql
SELECT name, engine, total_rows, formatReadableSize(total_bytes) AS size
FROM system.tables
WHERE database = 'shop_analytics'
ORDER BY name;
```

### Status Kafka consumer (tabel sumber)

```sql
SELECT database, table, consumer_id, assignments.topic, assignments.partition_id, assignments.current_offset
FROM system.kafka_consumers
WHERE database = 'shop_analytics';
```

### Cek query yang lagi jalan

```sql
SELECT
    query_id, user, elapsed, formatReadableSize(memory_usage) AS mem,
    substr(query, 1, 80) AS query_preview
FROM system.processes
ORDER BY elapsed DESC;
```

---

## 6. Bonus — Visualisasi Sederhana

### Bar chart order per status (pakai `bar()` function)

```sql
SELECT
    status,
    count() AS jumlah,
    bar(count(), 0, (SELECT max(c) FROM (SELECT count() AS c FROM shop_analytics.orders FINAL WHERE is_deleted=0 GROUP BY status)), 50) AS chart
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
GROUP BY status
ORDER BY jumlah DESC;
```

### Sparkline revenue per menit (1 jam terakhir)

```sql
SELECT
    arrayJoin([1]) AS row,
    groupArray(revenue) AS series,
    sparkbar(60)(minute, revenue) AS sparkline
FROM (
    SELECT
        toStartOfMinute(created_at) AS minute,
        sum(total_amount)           AS revenue
    FROM shop_analytics.orders FINAL
    WHERE is_deleted = 0 AND created_at >= now() - INTERVAL 1 HOUR
    GROUP BY minute
    ORDER BY minute
);
```

---

## 7. Kelebihan Time-Series ClickHouse

ClickHouse adalah columnar OLAP — query agregasi time-series jutaan/miliaran row sub-detik adalah strong suit-nya. Section ini demonstrasi pattern yang **lambat di Postgres tapi cepat di ClickHouse**.

### 7.1 Multi-window dashboard dalam satu pass

`countIf` / `sumIf` agregat banyak time-window dalam **satu kali baca tabel** — di Postgres butuh subquery atau CTE terpisah, di ClickHouse cukup satu SELECT.

```sql
SELECT
    countIf(created_at >= now() - INTERVAL 1 MINUTE)  AS orders_1m,
    countIf(created_at >= now() - INTERVAL 5 MINUTE)  AS orders_5m,
    countIf(created_at >= now() - INTERVAL 15 MINUTE) AS orders_15m,
    countIf(created_at >= now() - INTERVAL 1 HOUR)    AS orders_1h,
    sumIf(total_amount, created_at >= now() - INTERVAL 1 MINUTE)  AS rev_1m,
    sumIf(total_amount, created_at >= now() - INTERVAL 5 MINUTE)  AS rev_5m,
    sumIf(total_amount, created_at >= now() - INTERVAL 15 MINUTE) AS rev_15m,
    sumIf(total_amount, created_at >= now() - INTERVAL 1 HOUR)    AS rev_1h
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0;
```

### 7.2 `WITH FILL` — fill gap supaya time-series kontinu

Tanpa `WITH FILL`, menit yang tidak ada order akan hilang dari output (chart bolong). `WITH FILL STEP` auto-generate row dengan nilai 0.

```sql
SELECT
    toStartOfMinute(created_at) AS minute,
    count()                     AS orders,
    sum(total_amount)           AS revenue
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
  AND created_at >= now() - INTERVAL 30 MINUTE
GROUP BY minute
ORDER BY minute
WITH FILL STEP toIntervalMinute(1);
```

### 7.3 Moving Average & Running Total (window function)

Moving average 5-menit + cumulative revenue dalam satu query — useful untuk smoothing chart real-time.

```sql
SELECT
    toStartOfMinute(created_at) AS minute,
    count()                     AS orders,
    sum(total_amount)           AS revenue,
    round(avg(sum(total_amount)) OVER (
        ORDER BY toStartOfMinute(created_at)
        ROWS BETWEEN 4 PRECEDING AND CURRENT ROW
    ), 2)                       AS revenue_5min_ma,
    sum(sum(total_amount)) OVER (
        ORDER BY toStartOfMinute(created_at)
    )                           AS cumulative_revenue
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
  AND created_at >= now() - INTERVAL 30 MINUTE
GROUP BY minute
ORDER BY minute DESC;
```

### 7.4 Period-over-Period (current vs previous bucket)

`lagInFrame` ambil nilai bucket sebelumnya — gampang hitung delta, % growth, dll.

```sql
SELECT
    minute,
    orders,
    revenue,
    lagInFrame(orders,  1, 0) OVER (ORDER BY minute) AS prev_orders,
    lagInFrame(revenue, 1, 0) OVER (ORDER BY minute) AS prev_revenue,
    orders  - lagInFrame(orders,  1, 0) OVER (ORDER BY minute) AS delta_orders,
    round((revenue - lagInFrame(revenue, 1, 0) OVER (ORDER BY minute))
          / nullIf(lagInFrame(revenue, 1, 0) OVER (ORDER BY minute), 0) * 100, 2) AS pct_change
FROM (
    SELECT
        toStartOfMinute(created_at) AS minute,
        count()                     AS orders,
        sum(total_amount)           AS revenue
    FROM shop_analytics.orders FINAL
    WHERE is_deleted = 0
    GROUP BY minute
)
ORDER BY minute DESC
LIMIT 20;
```

### 7.5 Distribusi Quantile per Bucket (latency-style analysis)

Hitung p50/p95/p99 order value per menit dalam satu pass — pattern penting untuk SLO/latency monitoring.

```sql
SELECT
    toStartOfMinute(created_at) AS minute,
    count()                                      AS orders,
    round(quantile(0.5)(total_amount), 2)        AS p50,
    round(quantile(0.95)(total_amount), 2)       AS p95,
    round(quantile(0.99)(total_amount), 2)       AS p99,
    max(total_amount)                            AS max
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0
  AND created_at >= now() - INTERVAL 15 MINUTE
GROUP BY minute
ORDER BY minute DESC;
```

### 7.6 Top-N per Time Bucket (top produk per menit)

`row_number() OVER PARTITION BY` untuk top-N grouping — menggantikan loop aplikasi.

```sql
SELECT minute, rank, product, revenue
FROM (
    SELECT
        toStartOfMinute(o.created_at)                    AS minute,
        p.name                                           AS product,
        sum(oi.quantity * oi.unit_price)                 AS revenue,
        row_number() OVER (
            PARTITION BY toStartOfMinute(o.created_at)
            ORDER BY sum(oi.quantity * oi.unit_price) DESC
        )                                                AS rank
    FROM shop_analytics.order_items AS oi FINAL
    JOIN shop_analytics.orders     AS o FINAL ON o.id = oi.order_id
    JOIN shop_analytics.products   AS p FINAL ON p.id = oi.product_id
    WHERE oi.is_deleted = 0 AND o.is_deleted = 0 AND p.is_deleted = 0
      AND o.created_at >= now() - INTERVAL 10 MINUTE
    GROUP BY minute, p.name
)
WHERE rank <= 3
ORDER BY minute DESC, rank;
```

### 7.7 Funnel Analysis — `windowFunnel`

Hitung berapa order yang berhasil maju ke tiap stage status (`PLACED → PAID → SHIPPED → DELIVERED`) dalam window 1 jam. Ini built-in di ClickHouse, di Postgres butuh self-join berlapis yang lambat.

```sql
SELECT
    level,
    count() AS orders_reached
FROM (
    SELECT
        id,
        windowFunnel(3600)(toDateTime(updated_at),
            status = 'PLACED',
            status = 'PAID',
            status = 'SHIPPED',
            status = 'DELIVERED'
        ) AS level
    FROM shop_analytics.orders   -- TANPA FINAL: butuh semua versi status
    WHERE is_deleted = 0
    GROUP BY id
)
GROUP BY level
ORDER BY level;
```

Hasil: `level=4` artinya order tersebut menyentuh keempat stage berurutan. `level=2` artinya berhenti setelah `PAID`.

### 7.8 Anomaly Detection — Rolling Z-Score

Bandingkan throughput menit ini vs rolling mean ±10 menit terakhir, hitung z-score. `|z| > 2` = anomali.

```sql
SELECT
    minute,
    orders,
    round(rolling_avg, 2)             AS rolling_avg,
    round(rolling_std, 2)             AS rolling_std,
    round((orders - rolling_avg)
          / nullIf(rolling_std, 0), 2) AS zscore
FROM (
    SELECT
        toStartOfMinute(created_at) AS minute,
        count()                     AS orders,
        avg(count()) OVER (
            ORDER BY toStartOfMinute(created_at)
            ROWS BETWEEN 10 PRECEDING AND 1 PRECEDING
        ) AS rolling_avg,
        stddevPop(count()) OVER (
            ORDER BY toStartOfMinute(created_at)
            ROWS BETWEEN 10 PRECEDING AND 1 PRECEDING
        ) AS rolling_std
    FROM shop_analytics.orders FINAL
    WHERE is_deleted = 0
    GROUP BY minute
)
ORDER BY minute DESC
LIMIT 30;
```

### 7.9 Histogram Order Value (distribusi numerik)

Built-in `histogram(N)` bagi data ke N bin otomatis — pattern data exploration cepat.

```sql
SELECT
    arrayJoin(histogram(20)(toFloat64(total_amount))) AS bin,
    bin.1                                  AS lower,
    bin.2                                  AS upper,
    bin.3                                  AS frequency,
    bar(bin.3, 0, 50, 40)                  AS chart
FROM shop_analytics.orders FINAL
WHERE is_deleted = 0;
```

### 7.10 Heatmap order per (jam × kategori)

Pivot agregasi 2-dimensi pakai `groupArrayInsertAt` / `arrayJoin` — chart heatmap-ready.

```sql
SELECT
    toStartOfHour(o.created_at) AS hour,
    p.category                  AS category,
    count()                     AS orders,
    bar(count(), 0, 100, 30)    AS chart
FROM shop_analytics.order_items AS oi FINAL
JOIN shop_analytics.orders     AS o FINAL ON o.id = oi.order_id
JOIN shop_analytics.products   AS p FINAL ON p.id = oi.product_id
WHERE oi.is_deleted = 0 AND o.is_deleted = 0 AND p.is_deleted = 0
GROUP BY hour, category
ORDER BY hour DESC, orders DESC;
```

---

### Kenapa cepat?

- **Columnar storage** — query yang baca 3-5 kolom dari tabel 50 kolom hanya I/O 3-5 kolom.
- **Sparse primary index + skip indexes** — `ORDER BY (created_at, id)` bikin filter time-range super cepat (skip blok yang di luar range).
- **Vectorized execution** — agregasi & window function jalan di SIMD register, bukan row-by-row.
- **Built-in time/array/statistical functions** — `windowFunnel`, `quantileTDigest`, `lagInFrame`, `histogram`, `bar` — tidak perlu round-trip ke aplikasi.
- **Materialized View dengan AggregatingMergeTree** (advanced) — pre-aggregate tiap insert, dashboard query baca hasil pre-agregat → sub-millisecond bahkan di tabel besar.
