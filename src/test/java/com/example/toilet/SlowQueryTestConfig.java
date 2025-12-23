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

    public static long getSqlToiletListCount() {
        return INSTANCE == null ? 0L : INSTANCE.sqlToiletListCount.get();
    }

    public static long getSqlRatingAggCount() {
        return INSTANCE == null ? 0L : INSTANCE.sqlRatingAggCount.get();
    }

    public static void resetSqlCounters() {
        if (INSTANCE != null) {
            INSTANCE.sqlToiletListCount.set(0);
            INSTANCE.sqlRatingAggCount.set(0);
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
                            // no-op
                        }

                        @Override
                        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                            for (QueryInfo qi : queryInfoList) {
                                String sql = normalizeSql(qi.getQuery());
                                if (isToiletListQuery(sql)) {
                                    sqlToiletListCount.incrementAndGet();
                                }
                                if (isRatingAggQuery(sql)) {
                                    sqlRatingAggCount.incrementAndGet();
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
        return sql.replaceAll("\\s+", " ").trim();
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
}
