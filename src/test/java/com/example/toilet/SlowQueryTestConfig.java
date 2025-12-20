package com.example.toilet;

import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.util.List;

@TestConfiguration
public class SlowQueryTestConfig {
    private static final Logger log = LoggerFactory.getLogger(SlowQueryTestConfig.class);

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
                    QueryExecutionListener listener = new QueryExecutionListener() {
                        @Override
                        public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                            // no-op
                        }

                        @Override
                        public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
                            long elapsedMs = execInfo.getElapsedTime();
                            if (elapsedMs >= slowQueryThresholdMs) {
                                for (QueryInfo qi : queryInfoList) {
                                    log.warn("SLOW QUERY ({} ms) [{}] {}", elapsedMs, beanName, qi.getQuery());
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
