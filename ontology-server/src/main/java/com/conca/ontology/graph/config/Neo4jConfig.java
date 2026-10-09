package com.conca.ontology.graph.config;
import java.util.concurrent.TimeUnit;

import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Neo4j 连接池显式配置：driver 5.x 默认池（100 连接 / 60s 获取超时）对本地单用户偏宽松，
 * 池参数经 neo4j.pool.* 注入（application.yml），@Value 默认值与 yml 缺省一致。
 */
@Configuration
public class Neo4jConfig {

    @Bean(destroyMethod = "close")
    public Driver neo4jDriver(@Value("${neo4j.uri}") String uri,
                              @Value("${neo4j.authentication.username}") String username,
                              @Value("${neo4j.authentication.password}") String password,
                              @Value("${neo4j.pool.max-connection-pool-size:30}") int maxPoolSize,
                              @Value("${neo4j.pool.connection-acquisition-timeout-seconds:30}") long acquireTimeoutSec,
                              @Value("${neo4j.pool.connection-timeout-seconds:15}") long connTimeoutSec,
                              @Value("${neo4j.pool.max-connection-lifetime-minutes:30}") long maxLifetimeMin) {
        Config config = Config.builder()
                .withMaxConnectionPoolSize(maxPoolSize)
                .withConnectionAcquisitionTimeout(acquireTimeoutSec, TimeUnit.SECONDS)
                .withConnectionTimeout(connTimeoutSec, TimeUnit.SECONDS)
                .withMaxConnectionLifetime(maxLifetimeMin, TimeUnit.MINUTES)
                .build();
        return GraphDatabase.driver(uri, AuthTokens.basic(username, password), config);
    }
}
