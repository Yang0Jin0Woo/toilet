package com.example.toilet;

import com.example.toilet.service.ToiletService;
import lombok.extern.slf4j.Slf4j;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryCount;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.DataSourceQueryCountListener;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "slow.query.threshold.ms=100",
                "sql.log.enabled=true",
                "sql.log.group-by-only=false"
        }
)
@Import(QueryCountProofTest.QueryCountTestConfig.class)
@Slf4j
class QueryCountProofTest {

    private static final int NO_CACHE_REQUESTS = 10;
    private static final int WARMUP_REQUESTS = 5;
    private static final int WARM_REQUESTS = 5;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ToiletService toiletService;

    @Test
    void proveQueryCountsWithCacheOnOff() {
        ReflectionTestUtils.setField(toiletService, "ratingAggregationMode", "group");

        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", false);
        clearCache();
        for (int i = 1; i <= NO_CACHE_REQUESTS; i++) {
            QueryCounter.clear();
            Timing t = requestOnce("NO_CACHE", i);
            logQueryCount("NO_CACHE", i);
            log.info("NO_CACHE #{} aggMs={} totalMs={} testMs={}", i, t.aggMs, t.totalMs, t.testMs);
        }

        ReflectionTestUtils.setField(toiletService, "ratingCacheEnabled", true);
        clearCache();
        for (int i = 1; i <= WARMUP_REQUESTS; i++) {
            QueryCounter.clear();
            Timing t = requestOnce("WARMUP", i);
            logQueryCount("WARMUP", i);
            log.info("WARMUP #{} aggMs={} totalMs={} testMs={}", i, t.aggMs, t.totalMs, t.testMs);
        }
        for (int i = 1; i <= WARM_REQUESTS; i++) {
            QueryCounter.clear();
            Timing t = requestOnce("WARM", i);
            logQueryCount("WARM", i);
            log.info("WARM #{} aggMs={} totalMs={} testMs={}", i, t.aggMs, t.totalMs, t.testMs);
        }
    }

    private Timing requestOnce(String label, int iteration) {
        long startNanos = System.nanoTime();
        ResponseEntity<String> response = restTemplate.getForEntity("/toilets?withRatings=true", String.class);
        long testMs = (System.nanoTime() - startNanos) / 1_000_000;

        long totalMs = headerLong(response, "X-Total-Ms");
        long aggMs = headerLong(response, "X-Agg-Ms");

        log.info("REQ({} #{}) aggMs={} totalMs={} testMs={}", label, iteration, aggMs, totalMs, testMs);
        return new Timing(testMs, totalMs, aggMs);
    }

    private void logQueryCount(String label, int iteration) {
        QueryCount total = QueryCounter.getGrandTotal();
        if (total == null) {
            log.warn("QUERY_COUNT({} #{}) no-data", label, iteration);
            return;
        }
        log.info("QUERY_COUNT({} #{}) total={} select={} insert={} update={} delete={} timeMs={}",
                label, iteration,
                total.getTotal(),
                total.getSelect(),
                total.getInsert(),
                total.getUpdate(),
                total.getDelete(),
                total.getTime());
    }

    private long headerLong(ResponseEntity<String> response, String name) {
        String value = response.getHeaders().getFirst(name);
        if (value == null || value.isBlank()) return -1;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private void clearCache() {
        Object cache = ReflectionTestUtils.getField(toiletService, "ratingCache");
        if (cache instanceof Map<?, ?> map) {
            map.clear();
        }
    }

    private static final class Timing {
        private final long testMs;
        private final long totalMs;
        private final long aggMs;

        private Timing(long testMs, long totalMs, long aggMs) {
            this.testMs = testMs;
            this.totalMs = totalMs;
            this.aggMs = aggMs;
        }
    }

    private static final class QueryCounter {
        private static final ConcurrentMap<String, QueryCount> COUNTS = new ConcurrentHashMap<>();

        private static QueryCount getOrCreate(String name) {
            return COUNTS.computeIfAbsent(name, n -> new QueryCount());
        }

        private static void clear() {
            COUNTS.clear();
        }

        private static QueryCount getGrandTotal() {
            if (COUNTS.isEmpty()) {
                return null;
            }
            QueryCount total = new QueryCount();
            long select = 0;
            long insert = 0;
            long update = 0;
            long delete = 0;
            long other = 0;
            long statement = 0;
            long prepared = 0;
            long callable = 0;
            long all = 0;
            long success = 0;
            long failure = 0;
            long time = 0;
            for (QueryCount qc : COUNTS.values()) {
                select += qc.getSelect();
                insert += qc.getInsert();
                update += qc.getUpdate();
                delete += qc.getDelete();
                other += qc.getOther();
                statement += qc.getStatement();
                prepared += qc.getPrepared();
                callable += qc.getCallable();
                all += qc.getTotal();
                success += qc.getSuccess();
                failure += qc.getFailure();
                time += qc.getTime();
            }
            total.setSelect(select);
            total.setInsert(insert);
            total.setUpdate(update);
            total.setDelete(delete);
            total.setOther(other);
            total.setStatement(statement);
            total.setPrepared(prepared);
            total.setCallable(callable);
            total.setTotal(all);
            total.setSuccess(success);
            total.setFailure(failure);
            total.setTime(time);
            return total;
        }
    }

    @TestConfiguration
    static class QueryCountTestConfig {
        private static final DataSourceQueryCountListener COUNT_LISTENER = new DataSourceQueryCountListener();

        @Value("${slow.query.threshold.ms:100}")
        private long slowQueryThresholdMs;

        @Value("${sql.log.enabled:true}")
        private boolean sqlLogEnabled;

        @Value("${sql.log.group-by-only:false}")
        private boolean sqlGroupByOnly;

        @Value("${sql.log.max-length:300}")
        private int sqlLogMaxLength;

        @Bean
        public BeanPostProcessor dataSourceProxyBeanPostProcessor() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof DataSource dataSource && !isProxyDataSource(dataSource)) {
                        COUNT_LISTENER.setQueryCountStrategy(QueryCounter::getOrCreate);
                        QueryExecutionListener listener = new QueryExecutionListener() {
                            @Override
                            public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                                COUNT_LISTENER.beforeQuery(execInfo, queryInfoList);
                            }

                            @Override
                            public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                                COUNT_LISTENER.afterQuery(execInfo, queryInfoList);

                                long elapsedMs = execInfo.getElapsedTime();
                                if (elapsedMs >= slowQueryThresholdMs) {
                                    for (QueryInfo qi : queryInfoList) {
                                        log.warn("SLOW QUERY ({} ms) [{}] {}",
                                                elapsedMs, beanName, qi.getQuery());
                                    }
                                }

                                if (sqlLogEnabled) {
                                    int count = queryInfoList.size();
                                    log.info("SQL_COUNT [{}] {}", beanName, count);
                                    for (QueryInfo qi : queryInfoList) {
                                        String sql = normalizeSql(qi.getQuery());
                                        if (sqlGroupByOnly && !containsGroupBy(sql)) {
                                            continue;
                                        }
                                        log.info("SQL_LOG [{}] {}", beanName, sql);
                                        if (containsGroupBy(sql)) {
                                            log.info("GROUP_BY_DETECTED [{}] {}", beanName, sql);
                                        }
                                    }
                                }
                            }
                        };
                        return ProxyDataSourceBuilder.create(dataSource)
                                .name(beanName)
                                .listener(listener)
                                .build();
                    }
                    return bean;
                }
            };
        }

        private String normalizeSql(String sql) {
            if (sql == null) return "";
            String normalized = sql.replaceAll("\\s+", " ").trim();
            if (sqlLogMaxLength > 0 && normalized.length() > sqlLogMaxLength) {
                return normalized.substring(0, sqlLogMaxLength) + "...";
            }
            return normalized;
        }

        private boolean containsGroupBy(String sql) {
            if (sql == null) return false;
            return sql.toLowerCase().contains(" group by ");
        }

        private boolean isProxyDataSource(DataSource dataSource) {
            return dataSource.getClass().getName().contains("ProxyDataSource");
        }
    }
}
