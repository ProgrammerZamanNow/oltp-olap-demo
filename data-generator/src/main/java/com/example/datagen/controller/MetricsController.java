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
