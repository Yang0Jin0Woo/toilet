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

    private boolean isProxyDataSource(DataSource dataSource) {
        return dataSource.getClass().getName().contains("ProxyDataSource");
    }
}
