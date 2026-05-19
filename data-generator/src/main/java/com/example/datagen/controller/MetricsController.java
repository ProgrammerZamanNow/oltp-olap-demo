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
