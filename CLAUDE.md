# Project Context — OLTP → OLAP CDC Demo

Demo edukasi sinkronisasi real-time dari OLTP ke OLAP. Repo ini dibangun sebagai demo terbuka di GitHub: `git@github.com:ProgrammerZamanNow/oltp-olap-demo.git`.

## Preferensi Kerja

- Bahasa diskusi: **Bahasa Indonesia**.
- Backend code: **Spring Boot + Java 21**. Bukan Python/Node/Go.
- Container runtime: **Podman**, bukan Docker. Compose file diberi nama `podman-compose.yml`. Image selalu pakai prefix registry eksplisit (`docker.io/...`, `quay.io/...`) karena Podman tidak punya default registry.
- Git: **tidak boleh** ada `Co-Authored-By: Claude` atau mention "Generated with Claude Code" di commit message / PR body.

## Arsitektur

```
Spring Boot → PostgreSQL → Debezium (Kafka Connect) → Apache Kafka → ClickHouse
generator     (OLTP)       baca WAL via pgoutput       shop.public.*   Kafka engine + MV
                                                                       → ReplacingMergeTree
```

5 komponen, 4 container + 1 host-side Spring Boot app.

### Keputusan Teknis Penting

| Pilihan | Alasan |
|---------|--------|
| `apache/kafka:3.8.0` (bukan `bitnami/kafka:3.8`) | Tag bitnami sudah dihapus dari Docker Hub — manifest unknown. Env var beda: pakai `KAFKA_*` tanpa prefix `KAFKA_CFG_`. |
| Kafka KRaft mode (no Zookeeper) | Lebih ringan untuk demo. CLUSTER_ID di-hardcode supaya consistent restart. |
| `wal_level=logical` + `REPLICA IDENTITY FULL` | Wajib untuk Debezium pgoutput CDC. FULL supaya UPDATE/DELETE event bawa before-state lengkap (cost: WAL lebih besar, OK untuk demo). |
| `plugin.name=pgoutput` | Native ke Postgres modern (≥10), tidak butuh extension tambahan seperti wal2json. |
| `unwrap` SMT (ExtractNewRecordState) | Flatten payload Debezium jadi field-field tabel langsung. `delete.handling.mode=rewrite` → tambah kolom `__deleted` ('true'/'false') alih-alih tombstone. ClickHouse JSONEachRow gampang parse. |
| `decimal.handling.mode=double` | DECIMAL Postgres → JSON number. ClickHouse cast ke Decimal64 di MV (lossy tapi cukup untuk demo). Alternatif: `string` + parse manual untuk presisi penuh. |
| `time.precision.mode=connect` | TIDAK menghasilkan Int64 millis seperti dugaan awal — dengan `JsonConverter` schemaless tetap di-serialize sebagai **ISO 8601 string**. Karena itu Kafka engine table di ClickHouse pakai `String` + `parseDateTime64BestEffortOrZero(..., 3)`. |
| `ReplacingMergeTree(updated_at)` | Versi terbaru per PK menang setelah background merge. Query ad-hoc pakai `FINAL` untuk dedup at query-time. |
| Soft delete via `is_deleted` UInt8 | Bukan DELETE FROM. Filter `WHERE is_deleted = 0` saat query. Konsisten dengan event-based CDC. |
| Sintaks `FINAL` di ClickHouse | Harus **setelah alias**: `FROM tbl AS x FINAL`, BUKAN `FROM tbl FINAL AS x`. |
| Port Postgres host = `5433` (bukan 5432) | User punya Postgres lokal di 5432. Container internal tetap 5432. Generator's `application.yml` connect ke `jdbc:postgresql://localhost:5433/shop`. |
| Tidak ada volume Kafka di compose | Sengaja — restart `down` akan wipe topic & connector configs. Demo ulang dari snapshot lebih bersih. (Untuk production: tambahkan volume.) |

## Setup & Run

```bash
# Pastikan podman machine sudah running dengan memory cukup (minimal 4 GiB, ideal 6 GiB)
podman machine set --memory 6144 --cpus 4
podman machine start

# Stack up
make up         # podman compose up -d

# Tunggu ~30 detik, register Debezium connector
make register   # POST debezium/postgres-connector.json ke localhost:8083

# Cek connector RUNNING
make status

# Generator (terminal lain, butuh Java 21 + Maven)
make generator

# Eksplorasi data
make ch         # clickhouse-client interaktif (CLI)
make psql       # psql ke Postgres
make topics     # list Kafka topic
```

**ClickHouse Web UI**:
- `http://localhost:8123/play` — SQL editor (login `analytics`/`analytics`).
- `http://localhost:8123/dashboard` — monitoring built-in.

**Catatan Play UI**: tidak ada field database. Solusi: set field "url" jadi `http://localhost:8123/?database=shop_analytics`, atau prefix tabel di setiap query (`shop_analytics.<tabel>`). `USE shop_analytics;` tidak persist karena tiap query adalah HTTP request terpisah, dan multi-statement nggak diperbolehkan via Play.

**Memory requirement**: 2 GB di Podman machine TIDAK cukup (Kafka + Connect dua-duanya JVM ~512 MB-1 GB masing-masing + ClickHouse + Postgres). Minimum 4 GB, ideal 6 GB. Saat demo, 6 GB cukup nyaman dengan generator running.

## File Layout

```
.
├── podman-compose.yml             # 4 services: postgres, kafka, connect, clickhouse
├── Makefile                       # Shortcut command
├── README.md                      # Quick start + mermaid diagram + sample queries
├── clickhouse.md                  # 7 section query reference (lihat di bawah)
├── postgres/init.sql              # Schema + REPLICA IDENTITY FULL + publication + seed products
├── clickhouse/init.sql            # Kafka source tables + MV + ReplacingMergeTree targets
├── debezium/postgres-connector.json  # Source connector config (unwrap SMT)
└── data-generator/                # Spring Boot 3.3 + JPA + Datafaker
    ├── pom.xml
    └── src/main/...
        ├── entity/ (Customer, Product, Order, OrderItem)
        ├── repository/ (Spring Data JPA, dengan native query random pick)
        ├── service/DataGeneratorService.java  # @Scheduled every 2s
        └── DataGeneratorApplication.java
```

## clickhouse.md Struktur

7 section query siap copy-paste ke Play UI (semua pakai prefix `shop_analytics.<tabel>`):

1. **Verifikasi Pipeline** — count per tabel, freshness, CDC lag
2. **Business Analytics** — top product, revenue per category/kota, AOV, top spender
3. **Real-Time Monitoring** — throughput per 10s/menit, running total
4. **Data Quality / CDC Health** — count is_deleted, cek pending dedup, force `OPTIMIZE FINAL`, lihat semua versi sebuah row (TANPA `FINAL` di SELECT)
5. **Schema & System Inspection** — `system.tables`, `system.kafka_consumers`, `system.processes`
6. **Visualisasi Sederhana** — `bar()`, `sparkbar()`
7. **Kelebihan Time-Series ClickHouse** — 10 pattern: multi-window single pass (`countIf`/`sumIf`), `WITH FILL`, moving average + cumulative (window function), period-over-period (`lagInFrame`), quantile per bucket, top-N per bucket (`row_number`), funnel (`windowFunnel` — perlu `toDateTime` cast), anomaly z-score (rolling avg+stddev window), histogram (perlu `toFloat64` cast), heatmap

## Issue Log (Resolved)

Untuk referensi kalau besok ada problem mirip:

1. **`bitnami/kafka:3.8` manifest unknown** — tag dihapus dari Docker Hub. Diganti `apache/kafka:3.8.0` dengan env var berbeda (tanpa `KAFKA_CFG_` prefix). Path binary juga beda: `/opt/kafka/bin/` bukan `/opt/bitnami/kafka/bin/`.

2. **Postgres "role shop does not exist"** — bentrok dengan Postgres lokal di port 5432. Host port di compose dipindah ke `5433`, generator's `application.yml` mengikuti.

3. **ClickHouse 0 rows meski connector RUNNING** — saya salah asumsi `time.precision.mode=connect` emit Int64 millis. Aktualnya emit ISO 8601 string. Setelah ganti tipe Kafka table jadi `String` + `parseDateTime64BestEffort`, perlu **reset consumer offset** karena offset sebelumnya sudah committed past broken messages (`kafka_skip_broken_messages = 100`). Cara reset: DETACH MV + DETACH Kafka table → `kafka-consumer-groups.sh --reset-offsets --to-earliest` → ATTACH balik.

4. **`windowFunnel` error: Illegal type DateTime64** — cast `toDateTime(updated_at)`.

5. **`histogram()` error: Illegal type Decimal** — cast `toFloat64(total_amount)`.

6. **Sintaks `FROM tbl FINAL AS x` syntax error** — harus `FROM tbl AS x FINAL`.

7. **Connector hilang setelah `podman compose down`** — Kafka tidak punya volume, jadi topic `_connect_configs` ter-wipe. Solusi: re-register dengan `make register`. (Untuk persistensi: tambah volume Kafka di compose.)

## Alternatif Arsitektur (Belum Diimplementasi)

Pernah dibahas tapi tidak dieksekusi:

- **`MaterializedPostgreSQL` engine di ClickHouse** — replace Debezium + Kafka. ClickHouse baca WAL Postgres langsung. Lebih simpel (1 komponen vs 3), lebih sedikit latency. Tapi: marked experimental, tightly coupled (1 consumer only), no buffer kalau ClickHouse down. Setup hanya 1 DDL:
  ```sql
  CREATE DATABASE shop_cdc
  ENGINE = MaterializedPostgreSQL('postgres:5432', 'shop', 'shop', 'shop')
  SETTINGS materialized_postgresql_tables_list = 'customers,products,orders,order_items';
  ```

- **Named Collection untuk Kafka config** — sentralisasi `kafka_broker_list = 'kafka:29092'` yang sekarang duplikat di 4 tempat di `clickhouse/init.sql:20,40,59,77`. Sekali define, semua engine table reference nama collection.

- **Tabix / Metabase / Apache Superset** — UI yang lebih kaya dari Play UI bawaan. Tabix paling ringan (1 service tambahan di compose).

- **AggregatingMergeTree + Materialized View untuk pre-aggregation** — cocok untuk dashboard query sub-millisecond di tabel besar.

## Streaming Engine Lain di ClickHouse (Reference)

Selain `Kafka` engine:
- `RabbitMQ`, `NATS`, `AMQP` — pattern sama (engine table + MV)
- `S3Queue`, `AzureQueue` — auto-poll object storage untuk file baru
- `MaterializedPostgreSQL`, `MaterializedMySQL` — CDC langsung dari OLTP tanpa broker
- `URL`, `PostgreSQL`, `MySQL`, `MongoDB`, `Redis`, `ODBC`, `JDBC` — read-through external table (bukan streaming tapi sering dipakai bareng)
- Refreshable Materialized View — pull-based pseudo-streaming dengan jadwal `REFRESH EVERY N MINUTE`

## Status Akhir Sesi Demo

Demo berhasil run end-to-end:
- 4 container healthy
- Connector RUNNING dengan 0 error
- Generator pump ~5 order/2s
- ClickHouse terus accept data (sample: 43 customers, 219 orders, 454 order_items setelah ~2 menit run; sampai 4344 orders di test 1 jam)
- Sample analytics query (Top 5 produk by revenue) menunjukkan hasil yang masuk akal: Keyboard Mechanical RGB > Sepatu Sneakers > Mouse Wireless

Stack sudah ter-stop, container/volume/image semua sudah ter-hapus, Podman machine kembali ke setting awal (2 CPU, 2 GiB) dan stopped. Repo sudah di-push ke GitHub.

## Untuk Pindah Komputer

1. Clone repo: `git clone git@github.com:ProgrammerZamanNow/oltp-olap-demo.git`
2. Pastikan tools terinstall: Podman 4+, Java 21, Maven, `jq`, `psql` (opsional).
3. Bump podman machine memory ke 6 GiB: `podman machine set --memory 6144 && podman machine start`.
4. `make up` → tunggu 30 detik → `make register` → `make generator` di terminal lain.
5. Buka `http://localhost:8123/play` untuk eksplorasi.
6. File CLAUDE.md ini akan otomatis di-load Claude Code untuk konteks proyek.
