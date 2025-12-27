package com.example.toilet;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@TestConfiguration
public class SlowQueryTestConfig {
    private static final Logger log = LoggerFactory.getLogger(SlowQueryTestConfig.class);
    private static volatile SlowQueryTestConfig INSTANCE;

    private final AtomicLong sqlToiletListCount = new AtomicLong();
    private final AtomicLong sqlRatingAggCount = new AtomicLong();
    private final AtomicLong sqlStatementCount = new AtomicLong();

    private final AtomicLong sqlToiletListElapsedMs = new AtomicLong();
    private final AtomicLong sqlRatingAggElapsedMs = new AtomicLong();
    private final AtomicLong sqlStatementElapsedMs = new AtomicLong();
    private final AtomicLong sqlInFlight = new AtomicLong();
    private final AtomicLong sqlInFlightMax = new AtomicLong();

    public static long getSqlToiletListCount() {
        return INSTANCE == null ? 0L : INSTANCE.sqlToiletListCount.get();
    }

    public static long getSqlRatingAggCount() {
        return INSTANCE == null ? 0L : INSTANCE.sqlRatingAggCount.get();
    }

    public static long getSqlStatementCount() {
        return INSTANCE == null ? 0L : INSTANCE.sqlStatementCount.get();
    }

    public static long getSqlToiletListElapsedMs() {
        return INSTANCE == null ? 0L : INSTANCE.sqlToiletListElapsedMs.get();
    }

    public static long getSqlRatingAggElapsedMs() {
        return INSTANCE == null ? 0L : INSTANCE.sqlRatingAggElapsedMs.get();
    }

    public static long getSqlStatementElapsedMs() {
        return INSTANCE == null ? 0L : INSTANCE.sqlStatementElapsedMs.get();
    }

    public static long getSqlInFlightMax() {
        return INSTANCE == null ? 0L : INSTANCE.sqlInFlightMax.get();
    }

    public static void resetSqlCounters() {
        if (INSTANCE != null) {
            INSTANCE.sqlToiletListCount.set(0);
            INSTANCE.sqlRatingAggCount.set(0);
            INSTANCE.sqlStatementCount.set(0);
            INSTANCE.sqlToiletListElapsedMs.set(0);
            INSTANCE.sqlRatingAggElapsedMs.set(0);
            INSTANCE.sqlStatementElapsedMs.set(0);
            INSTANCE.sqlInFlight.set(0);
            INSTANCE.sqlInFlightMax.set(0);
        }
    }

    @Bean
    public BeanPostProcessor dataSourceProxyBeanPostProcessor() {
        INSTANCE = this;
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !isProxyDataSource(dataSource)) {
                    QueryExecutionListener listener = new QueryExecutionListener() {
                        @Override
                        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                            long size = queryInfoList == null ? 0L : queryInfoList.size();
                            long now = sqlInFlight.addAndGet(size);
                            updateMax(sqlInFlightMax, now);
                        }

                        @Override
                        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                            long elapsedMs = execInfo.getElapsedTime();
                            sqlStatementCount.addAndGet(queryInfoList.size());
                            sqlStatementElapsedMs.addAndGet(elapsedMs);
                            for (QueryInfo qi : queryInfoList) {
                                String sql = normalizeSql(qi.getQuery());
                                if (isToiletListQuery(sql)) {
                                    sqlToiletListCount.incrementAndGet();
                                    sqlToiletListElapsedMs.addAndGet(elapsedMs);
                                }
                                if (isRatingAggQuery(sql)) {
                                    sqlRatingAggCount.incrementAndGet();
                                    sqlRatingAggElapsedMs.addAndGet(elapsedMs);
                                }
                            }
                            long size = queryInfoList == null ? 0L : queryInfoList.size();
                            sqlInFlight.addAndGet(-size);
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
        return sql.replaceAll("\s+", " ").trim();
    }

    private boolean isToiletListQuery(String sql) {
        if (sql == null) return false;
        String lowered = sql.toLowerCase();
        return lowered.contains(" from toilet ")
                && !lowered.contains(" join ")
                && !lowered.contains(" count(");
    }

    private boolean isRatingAggQuery(String sql) {
        if (sql == null) return false;
        String lowered = sql.toLowerCase();
        return lowered.contains(" from review ");
    }

    private boolean isProxyDataSource(DataSource dataSource) {
        return dataSource.getClass().getName().contains("ProxyDataSource");
    }

    private static void updateMax(AtomicLong max, long value) {
        long prev;
        do {
            prev = max.get();
            if (value <= prev) {
                return;
            }
        } while (!max.compareAndSet(prev, value));
    }
}
