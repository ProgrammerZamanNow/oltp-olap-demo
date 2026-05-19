package com.example.datagen.controller;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final JdbcTemplate ch;

    public MetricsController(@Qualifier("clickhouseJdbc") JdbcTemplate ch) {
        this.ch = ch;
    }

    /**
     * 8 metric (orders+revenue × 1m/5m/15m/1h) dalam SATU query single-pass.
     * Showcase: countIf / sumIf — di Postgres butuh subquery/CTE bertingkat.
     */
    @GetMapping("/windows")
    public Map<String, Object> windows() {
        String sql = """
            SELECT
              countIf(created_at >= now() - INTERVAL 1 MINUTE)         AS orders_1m,
              countIf(created_at >= now() - INTERVAL 5 MINUTE)         AS orders_5m,
              countIf(created_at >= now() - INTERVAL 15 MINUTE)        AS orders_15m,
              countIf(created_at >= now() - INTERVAL 1 HOUR)           AS orders_1h,
              sumIf(total_amount, created_at >= now() - INTERVAL 1 MINUTE)  AS rev_1m,
              sumIf(total_amount, created_at >= now() - INTERVAL 5 MINUTE)  AS rev_5m,
              sumIf(total_amount, created_at >= now() - INTERVAL 15 MINUTE) AS rev_15m,
              sumIf(total_amount, created_at >= now() - INTERVAL 1 HOUR)    AS rev_1h
            FROM shop_analytics.orders FINAL
            WHERE is_deleted = 0
            """;
        long t0 = System.nanoTime();
        Map<String, Object> row = ch.queryForMap(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return response(row, elapsedMs, sql);
    }

    /**
     * Throughput per menit, optional WITH FILL untuk gap-fill menit kosong.
     * Showcase: WITH FILL STEP — Postgres butuh generate_series + LEFT JOIN.
     */
    @GetMapping("/throughput")
    public Map<String, Object> throughput(
            @RequestParam(defaultValue = "true") boolean withFill,
            @RequestParam(defaultValue = "60") int minutes) {

        int safe = clamp(minutes, 5, 360);
        String fillClause = withFill
                ? "WITH FILL FROM toStartOfMinute(now() - INTERVAL " + safe + " MINUTE) "
                + "TO toStartOfMinute(now() + INTERVAL 1 MINUTE) STEP toIntervalMinute(1)"
                : "";

        String sql = """
            SELECT
              toStartOfMinute(created_at) AS minute,
              count()                     AS orders,
              sum(total_amount)           AS revenue
            FROM shop_analytics.orders FINAL
            WHERE is_deleted = 0
              AND created_at >= now() - INTERVAL %d MINUTE
            GROUP BY minute
            ORDER BY minute ASC
            %s
            """.formatted(safe, fillClause);

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = ch.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        var body = response(rows, elapsedMs, sql);
        body.put("withFill", withFill);
        body.put("minutes", safe);
        return body;
    }

    /**
     * Δ orders per menit (turunan pertama) — velocity / acceleration.
     * Showcase: lagInFrame window function — bisa di Postgres tapi sini lebih cepat.
     */
    @GetMapping("/velocity")
    public Map<String, Object> velocity(@RequestParam(defaultValue = "30") int minutes) {
        int safe = clamp(minutes, 5, 180);

        String sql = """
            SELECT
              minute,
              orders,
              orders - lagInFrame(orders, 1, 0) OVER (ORDER BY minute) AS velocity
            FROM (
              SELECT
                toStartOfMinute(created_at) AS minute,
                count()                     AS orders
              FROM shop_analytics.orders FINAL
              WHERE is_deleted = 0
                AND created_at >= now() - INTERVAL %d MINUTE
              GROUP BY minute
            )
            ORDER BY minute ASC
            """.formatted(safe);

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = ch.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return response(rows, elapsedMs, sql);
    }

    /**
     * Rolling z-score per menit — anomaly detection style.
     * Showcase: avg() / stddevPop() OVER (ROWS BETWEEN ... PRECEDING).
     * |z| > 2 dianggap anomali (spike / drop).
     */
    @GetMapping("/anomaly")
    public Map<String, Object> anomaly(@RequestParam(defaultValue = "60") int minutes) {
        int safe = clamp(minutes, 15, 360);

        String sql = """
            SELECT
              minute,
              orders,
              round(rolling_avg, 2) AS rolling_avg,
              round(rolling_std, 2) AS rolling_std,
              round((orders - rolling_avg) / nullIf(rolling_std, 0), 2) AS zscore
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
                AND created_at >= now() - INTERVAL %d MINUTE
              GROUP BY minute
            )
            ORDER BY minute ASC
            """.formatted(safe);

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = ch.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return response(rows, elapsedMs, sql);
    }

    /**
     * Funnel analysis PLACED → PAID → SHIPPED → DELIVERED dalam satu query.
     * Showcase: windowFunnel(N) — di Postgres butuh self-join berlapis.
     * Sekaligus return stats agregat: cancelled, abandoned, completed, dst.
     */
    @GetMapping("/funnel")
    public Map<String, Object> funnel() {
        // 1) Per-order funnel level + flag, dipakai untuk stats DAN bars
        String perOrderCte = """
            WITH per_order AS (
              SELECT
                id,
                windowFunnel(3600)(toDateTime(updated_at),
                  status = 'PLACED',
                  status = 'PAID',
                  status = 'SHIPPED',
                  status = 'DELIVERED'
                ) AS funnel_level,
                maxIf(1, status = 'CANCELLED') AS was_cancelled,
                countIf(status = 'PAID')       AS paid_count
              FROM shop_analytics.orders_events
              WHERE is_deleted = 0
              GROUP BY id
            )
            """;

        String stagesSql = perOrderCte + """
            SELECT stage, pos, reached
            FROM (
              SELECT 'PLACED'    AS stage, 1 AS pos, countIf(funnel_level >= 1) AS reached FROM per_order
              UNION ALL
              SELECT 'PAID',     2,                  countIf(funnel_level >= 2)             FROM per_order
              UNION ALL
              SELECT 'SHIPPED',  3,                  countIf(funnel_level >= 3)             FROM per_order
              UNION ALL
              SELECT 'DELIVERED',4,                  countIf(funnel_level = 4)              FROM per_order
            )
            ORDER BY pos
            """;

        String statsSql = perOrderCte + """
            SELECT
              count()                                              AS total_orders,
              countIf(funnel_level = 4)                            AS completed,
              countIf(was_cancelled = 1)                           AS ever_cancelled,
              countIf(funnel_level = 1 AND was_cancelled = 1)      AS abandoned_at_placed,
              countIf(funnel_level >= 2 AND was_cancelled = 1)     AS cancelled_after_paid,
              countIf(funnel_level >= 3 AND paid_count = 0)        AS shipped_without_paid,
              countIf(funnel_level = 1 AND was_cancelled = 0)      AS stuck_at_placed,
              round(countIf(funnel_level = 4) * 100.0
                    / nullIf(countIf(funnel_level >= 1), 0), 2)    AS conversion_pct
            FROM per_order
            """;

        long t0 = System.nanoTime();
        List<Map<String, Object>> stages = ch.queryForList(stagesSql);
        Map<String, Object> stats = ch.queryForMap(statsSql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        var data = new LinkedHashMap<String, Object>();
        data.put("stages", stages);
        data.put("stats", stats);

        var body = response(data, elapsedMs, stagesSql + ";\n\n" + statsSql);
        return body;
    }

    /**
     * Time-to-conversion histogram — distribusi durasi PLACED → DELIVERED.
     * Showcase: histogram(N) built-in auto-binning — Postgres butuh width_bucket manual.
     */
    @GetMapping("/conversion-histogram")
    public Map<String, Object> conversionHistogram(@RequestParam(defaultValue = "20") int bins) {
        int safe = clamp(bins, 5, 60);
        String sql = """
            SELECT
              bin.1 AS lower,
              bin.2 AS upper,
              bin.3 AS frequency
            FROM (
              SELECT arrayJoin(histogram(%d)(toFloat64(seconds_to_delivered))) AS bin
              FROM (
                SELECT
                  id,
                  minIf(updated_at, status = 'PLACED')    AS placed_at,
                  minIf(updated_at, status = 'DELIVERED') AS delivered_at,
                  dateDiff('second', placed_at, delivered_at) AS seconds_to_delivered
                FROM shop_analytics.orders_events
                WHERE is_deleted = 0
                GROUP BY id
                HAVING toUnixTimestamp(placed_at) > 0
                   AND toUnixTimestamp(delivered_at) > 0
                   AND delivered_at > placed_at
              )
            )
            ORDER BY lower
            """.formatted(safe);

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = ch.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        var body = response(rows, elapsedMs, sql);
        body.put("bins", safe);
        return body;
    }

    /**
     * Quantile bands per menit — P50 / P95 / P99 order value.
     * Showcase: quantile(0.5/0.95/0.99) per bucket — sub-second di jutaan row,
     * vs Postgres percentile_cont yang lambat.
     */
    @GetMapping("/quantiles")
    public Map<String, Object> quantiles(@RequestParam(defaultValue = "60") int minutes) {
        int safe = clamp(minutes, 10, 360);
        String sql = """
            SELECT
              toStartOfMinute(created_at)                  AS minute,
              count()                                       AS orders,
              round(quantile(0.5)(total_amount), 2)         AS p50,
              round(quantile(0.95)(total_amount), 2)        AS p95,
              round(quantile(0.99)(total_amount), 2)        AS p99,
              round(max(total_amount), 2)                   AS max_amount
            FROM shop_analytics.orders FINAL
            WHERE is_deleted = 0
              AND created_at >= now() - INTERVAL %d MINUTE
            GROUP BY minute
            ORDER BY minute ASC
            """.formatted(safe);

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = ch.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return response(rows, elapsedMs, sql);
    }

    /**
     * Period-over-period — current minute vs previous, dengan % delta.
     * Showcase: lagInFrame() window function.
     */
    @GetMapping("/period-over-period")
    public Map<String, Object> periodOverPeriod(@RequestParam(defaultValue = "60") int minutes) {
        int safe = clamp(minutes, 10, 360);
        String sql = """
            SELECT
              minute,
              orders,
              round(revenue, 2)                                                   AS revenue,
              lagInFrame(orders, 1, 0) OVER (ORDER BY minute)                     AS prev_orders,
              round(lagInFrame(revenue, 1, 0) OVER (ORDER BY minute), 2)          AS prev_revenue,
              orders - lagInFrame(orders, 1, 0) OVER (ORDER BY minute)            AS delta_orders,
              round(revenue - lagInFrame(revenue, 1, 0) OVER (ORDER BY minute), 2) AS delta_revenue,
              round((revenue - lagInFrame(revenue, 1, 0) OVER (ORDER BY minute))
                    / nullIf(lagInFrame(revenue, 1, 0) OVER (ORDER BY minute), 0) * 100, 2) AS pct_change
            FROM (
              SELECT
                toStartOfMinute(created_at) AS minute,
                count()                     AS orders,
                sum(total_amount)           AS revenue
              FROM shop_analytics.orders FINAL
              WHERE is_deleted = 0
                AND created_at >= now() - INTERVAL %d MINUTE
              GROUP BY minute
            )
            ORDER BY minute DESC
            LIMIT 20
            """.formatted(safe);

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = ch.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return response(rows, elapsedMs, sql);
    }

    /**
     * Top-3 produk per menit pakai topKWeighted (approximate algorithm).
     * Showcase: topKWeighted(N)(key, weight) built-in — Postgres butuh window
     * row_number() OVER PARTITION BY yang lebih lambat & verbose.
     */
    @GetMapping("/top-per-bucket")
    public Map<String, Object> topPerBucket(@RequestParam(defaultValue = "15") int minutes) {
        int safe = clamp(minutes, 5, 60);
        String sql = """
            SELECT
              toStartOfMinute(o.created_at)                                  AS minute,
              topKWeighted(3)(p.name, toUInt64(oi.quantity * oi.unit_price)) AS top3_products,
              round(sum(oi.quantity * oi.unit_price), 2)                     AS minute_revenue,
              count(DISTINCT o.id)                                           AS order_count
            FROM shop_analytics.order_items AS oi FINAL
            JOIN shop_analytics.orders     AS o  FINAL ON o.id = oi.order_id
            JOIN shop_analytics.products   AS p  FINAL ON p.id = oi.product_id
            WHERE oi.is_deleted = 0 AND o.is_deleted = 0 AND p.is_deleted = 0
              AND o.created_at >= now() - INTERVAL %d MINUTE
            GROUP BY minute
            ORDER BY minute DESC
            """.formatted(safe);

        long t0 = System.nanoTime();
        List<Map<String, Object>> rows = ch.queryForList(sql);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return response(rows, elapsedMs, sql);
    }

    /**
     * Cardinality comparison — uniqExact vs uniqHLL12 (HyperLogLog).
     * Showcase: untuk juta-an row, HLL bisa 10–100× lebih cepat dengan
     * error rate ~0.81% (HLL12 → 2^12 buckets). Run 2 query terpisah supaya
     * elapsed time-nya bisa dibandingkan.
     */
    @GetMapping("/cardinality")
    public Map<String, Object> cardinality() {
        String exactSql = """
            SELECT
              uniqExact(customer_id) AS customers,
              uniqExact(id)          AS orders
            FROM shop_analytics.orders FINAL
            WHERE is_deleted = 0
            """;
        String hllSql = """
            SELECT
              uniqHLL12(customer_id) AS customers,
              uniqHLL12(id)          AS orders
            FROM shop_analytics.orders FINAL
            WHERE is_deleted = 0
            """;

        long t1 = System.nanoTime();
        Map<String, Object> exact = ch.queryForMap(exactSql);
        long exactMs = (System.nanoTime() - t1) / 1_000_000;

        long t2 = System.nanoTime();
        Map<String, Object> hll = ch.queryForMap(hllSql);
        long hllMs = (System.nanoTime() - t2) / 1_000_000;

        var data = new LinkedHashMap<String, Object>();
        data.put("exact", exact);
        data.put("hll", hll);
        data.put("exactMs", exactMs);
        data.put("hllMs", hllMs);
        data.put("speedup", hllMs > 0 ? ((double) exactMs / hllMs) : 1.0);

        return response(data, exactMs + hllMs, exactSql + ";\n\n" + hllSql);
    }

    /**
     * Pre-aggregation showcase: SAMA query, dua source.
     *  - RAW: scan ulang orders_events, group + agregat tiap call.
     *  - PRE-AGG: baca state yang sudah di-pre-compute via
     *    AggregatingMergeTree, hanya butuh sumMerge/countMerge.
     * Untuk juta-an row, pre-agg bisa 50-100× lebih cepat.
     */
    @GetMapping("/preaggregate")
    public Map<String, Object> preAggregate(@RequestParam(defaultValue = "60") int minutes) {
        int safe = clamp(minutes, 5, 1440);

        String rawSql = """
            SELECT
              toStartOfMinute(created_at) AS minute,
              count()                     AS events,
              round(sum(total_amount), 2) AS revenue,
              round(quantile(0.95)(toFloat64(total_amount)), 2) AS p95
            FROM shop_analytics.orders_events
            WHERE created_at >= now() - INTERVAL %d MINUTE
            GROUP BY minute
            ORDER BY minute ASC
            """.formatted(safe);

        String preAggSql = """
            SELECT
              minute,
              countMerge(events_count)                       AS events,
              round(sumMerge(revenue_sum), 2)                AS revenue,
              round(quantileMerge(p95_amount), 2)            AS p95
            FROM shop_analytics.orders_per_minute
            WHERE minute >= now() - INTERVAL %d MINUTE
            GROUP BY minute
            ORDER BY minute ASC
            """.formatted(safe);

        long t1 = System.nanoTime();
        List<Map<String, Object>> rawRows = ch.queryForList(rawSql);
        long rawMs = (System.nanoTime() - t1) / 1_000_000;

        long t2 = System.nanoTime();
        List<Map<String, Object>> preAggRows = ch.queryForList(preAggSql);
        long preAggMs = (System.nanoTime() - t2) / 1_000_000;

        long rawEvents = sumLong(rawRows, "events");
        long preAggEvents = sumLong(preAggRows, "events");

        var raw = new LinkedHashMap<String, Object>();
        raw.put("rows", rawRows);
        raw.put("elapsedMs", rawMs);
        raw.put("totalEvents", rawEvents);
        raw.put("sql", rawSql.strip());

        var preAgg = new LinkedHashMap<String, Object>();
        preAgg.put("rows", preAggRows);
        preAgg.put("elapsedMs", preAggMs);
        preAgg.put("totalEvents", preAggEvents);
        preAgg.put("sql", preAggSql.strip());

        var body = new LinkedHashMap<String, Object>();
        body.put("raw", raw);
        body.put("preAgg", preAgg);
        body.put("speedup", preAggMs > 0 ? ((double) rawMs / preAggMs) : 1.0);
        body.put("minutes", safe);
        // Match check — allow small diff because raw vs preagg now() bisa beda mikrodetik
        long diff = Math.abs(rawEvents - preAggEvents);
        body.put("eventsDiff", diff);
        body.put("matchPct", rawEvents > 0 ? (100.0 * (rawEvents - diff) / rawEvents) : 100.0);
        return body;
    }

    /**
     * Real dashboard powered by pre-aggregated state.
     * Tiles & chart cuma baca dari `orders_per_minute` (AggregatingMergeTree).
     * Showcase: state COMPOSABLE — bisa re-aggregate ke bucket apapun
     * (1m / 5m / 15m / 1h / 1d) lewat GROUP BY toStartOfInterval, dari source yg sama.
     */
    @GetMapping("/preaggregate-live")
    public Map<String, Object> preAggregateLive(
            @RequestParam(defaultValue = "1") int resolution,
            @RequestParam(defaultValue = "60") int window) {

        int safeRes = clamp(resolution, 1, 1440);
        int safeWin = clamp(window, 5, 10080);

        // 1) Tile metrics — 4 window berbeda dalam satu UNION query
        String tilesSql = """
            SELECT 'today' AS bucket_label,
                   countMerge(events_count) AS events,
                   round(sumMerge(revenue_sum), 2) AS revenue,
                   round(quantileMerge(p95_amount), 2) AS p95
            FROM shop_analytics.orders_per_minute
            WHERE minute >= toStartOfDay(now())
            UNION ALL
            SELECT 'hour',
                   countMerge(events_count),
                   round(sumMerge(revenue_sum), 2),
                   round(quantileMerge(p95_amount), 2)
            FROM shop_analytics.orders_per_minute
            WHERE minute >= now() - INTERVAL 1 HOUR
            UNION ALL
            SELECT 'last5m',
                   countMerge(events_count),
                   round(sumMerge(revenue_sum), 2),
                   round(quantileMerge(p95_amount), 2)
            FROM shop_analytics.orders_per_minute
            WHERE minute >= now() - INTERVAL 5 MINUTE
            UNION ALL
            SELECT 'prev5m',
                   countMerge(events_count),
                   round(sumMerge(revenue_sum), 2),
                   round(quantileMerge(p95_amount), 2)
            FROM shop_analytics.orders_per_minute
            WHERE minute >= now() - INTERVAL 10 MINUTE
              AND minute <  now() - INTERVAL 5 MINUTE
            """;

        long t0 = System.nanoTime();
        List<Map<String, Object>> tilesRaw = ch.queryForList(tilesSql);
        long tilesMs = (System.nanoTime() - t0) / 1_000_000;

        var tiles = new LinkedHashMap<String, Map<String, Object>>();
        for (var r : tilesRaw) {
            tiles.put((String) r.get("bucket_label"), Map.of(
                "events", r.getOrDefault("events", 0),
                "revenue", r.getOrDefault("revenue", 0),
                "p95", r.getOrDefault("p95", 0)
            ));
        }

        // 2) Multi-resolution chart — SAMA state, GROUP BY berbeda
        String chartSql = """
            SELECT
              toStartOfInterval(minute, INTERVAL %d MINUTE) AS bucket,
              countMerge(events_count)                       AS events,
              round(sumMerge(revenue_sum), 2)                AS revenue,
              round(quantileMerge(p95_amount), 2)            AS p95
            FROM shop_analytics.orders_per_minute
            WHERE minute >= now() - INTERVAL %d MINUTE
            GROUP BY bucket
            ORDER BY bucket ASC
            """.formatted(safeRes, safeWin);

        long t1 = System.nanoTime();
        List<Map<String, Object>> chartRows = ch.queryForList(chartSql);
        long chartMs = (System.nanoTime() - t1) / 1_000_000;

        var body = new LinkedHashMap<String, Object>();
        body.put("tiles", tiles);
        body.put("tilesMs", tilesMs);
        body.put("chart", chartRows);
        body.put("chartMs", chartMs);
        body.put("resolution", safeRes);
        body.put("window", safeWin);
        body.put("tilesSql", tilesSql.strip());
        body.put("chartSql", chartSql.strip());
        return body;
    }

    /**
     * Time-travel snapshot: state lengkap order pada timestamp T tertentu.
     * Showcase: argMax(value, sortKey) — untuk tiap id, ambil row dengan
     * updated_at terbesar yang masih <= T. Hasil = state seperti waktu itu.
     * Pattern event sourcing + point-in-time query.
     */
    @GetMapping("/timetravel")
    public Map<String, Object> timeTravel(@RequestParam(required = false) Long at) {
        long atTime = (at != null) ? at : (System.currentTimeMillis() / 1000);

        // 1) Bounds untuk slider (min event time, sekarang)
        String boundsSql = """
            SELECT
              toUnixTimestamp(min(updated_at)) AS min_ts,
              toUnixTimestamp(now())            AS max_ts
            FROM shop_analytics.orders_events
            """;
        Map<String, Object> bounds = ch.queryForMap(boundsSql);

        // 2) Snapshot stats pada T — single query semua aggregate
        String statsSql = """
            WITH snap AS (
              SELECT
                id,
                argMax(status, updated_at)       AS status,
                argMax(total_amount, updated_at) AS amount,
                argMax(customer_id, updated_at)  AS customer_id,
                argMax(is_deleted, updated_at)   AS is_deleted
              FROM shop_analytics.orders_events
              WHERE updated_at <= fromUnixTimestamp(?)
              GROUP BY id
            )
            SELECT
              count()                                                AS total_orders,
              countIf(status = 'PLACED')                             AS placed,
              countIf(status = 'PAID')                               AS paid,
              countIf(status = 'SHIPPED')                            AS shipped,
              countIf(status = 'DELIVERED')                          AS delivered,
              countIf(status = 'CANCELLED')                          AS cancelled,
              round(sum(amount), 2)                                  AS total_revenue,
              round(avg(amount), 2)                                  AS avg_amount,
              round(median(amount), 2)                               AS median_amount,
              round(quantile(0.95)(toFloat64(amount)), 2)            AS p95_amount,
              round(max(amount), 2)                                  AS max_amount,
              round(min(amount), 2)                                  AS min_amount,
              uniqExact(customer_id)                                 AS unique_customers,
              round(sumIf(amount, status IN ('PAID','SHIPPED','DELIVERED')), 2) AS revenue_completed,
              round(sumIf(amount, status = 'CANCELLED'), 2)          AS revenue_lost
            FROM snap
            WHERE is_deleted = 0
            """;

        long t0 = System.nanoTime();
        Map<String, Object> stats = ch.queryForMap(statsSql, atTime);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        var body = new LinkedHashMap<String, Object>();
        body.put("at", atTime);
        body.put("bounds", bounds);
        body.put("stats", stats);
        body.put("elapsedMs", elapsedMs);
        body.put("sql", statsSql.strip().replace("?", String.valueOf(atTime)));
        return body;
    }

    private static long sumLong(List<Map<String, Object>> rows, String key) {
        long total = 0;
        for (var r : rows) {
            Object v = r.get(key);
            if (v instanceof Number n) total += n.longValue();
        }
        return total;
    }

    private Map<String, Object> response(Object data, long elapsedMs, String sql) {
        var body = new LinkedHashMap<String, Object>();
        body.put("data", data);
        body.put("elapsedMs", elapsedMs);
        body.put("sql", sql.strip());
        return body;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
