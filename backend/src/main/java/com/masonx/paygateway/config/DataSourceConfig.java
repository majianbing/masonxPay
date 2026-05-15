package com.masonx.paygateway.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableReferenceRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.single.config.SingleRuleConfiguration;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

@Configuration
public class DataSourceConfig {

    private static final int PAYMENT_SHARD_COUNT = 64;

    @Bean
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties physicalDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource flywayDataSource(DataSourceProperties physicalDataSourceProperties) {
        return physicalDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean
    @Primary
    @Lazy
    public DataSource dataSource(@Qualifier("flywayDataSource") DataSource physicalDataSource) throws SQLException {
        Map<String, DataSource> dataSources = new LinkedHashMap<>();
        dataSources.put("ds_0", physicalDataSource);

        Properties props = new Properties();
        props.setProperty("sql-show", "false");

        return ShardingSphereDataSourceFactory.createDataSource(
                "masonxpay_payment_core",
                dataSources,
                List.of(paymentShardingRule(), singleTableRule()),
                props);
    }

    private RuleConfiguration paymentShardingRule() {
        ShardingRuleConfiguration result = new ShardingRuleConfiguration();

        ShardingTableRuleConfiguration intents = new ShardingTableRuleConfiguration(
                "payment_intents", actualDataNodes("payment_intents"));
        intents.setTableShardingStrategy(new StandardShardingStrategyConfiguration(
                "id", "payment_intent_inline"));

        ShardingTableRuleConfiguration requests = new ShardingTableRuleConfiguration(
                "payment_requests", actualDataNodes("payment_requests"));
        requests.setTableShardingStrategy(new StandardShardingStrategyConfiguration(
                "payment_intent_id", "payment_request_inline"));

        result.setTables(List.of(intents, requests));
        result.setBindingTableGroups(List.of(new ShardingTableReferenceRuleConfiguration(
                "payment_core", "payment_intents,payment_requests")));
        result.setShardingAlgorithms(shardingAlgorithms());
        return result;
    }

    private SingleRuleConfiguration singleTableRule() {
        return new SingleRuleConfiguration(singleTables(), "ds_0");
    }

    private Collection<String> singleTables() {
        return List.of(
                "ds_0.admin_audit_logs",
                "ds_0.admin_users",
                "ds_0.api_keys",
                "ds_0.gateway_events",
                "ds_0.gateway_logs",
                "ds_0.gateway_logs_2025_h2",
                "ds_0.gateway_logs_2026_h1",
                "ds_0.gateway_logs_2026_h2",
                "ds_0.gateway_logs_legacy",
                "ds_0.invite_tokens",
                "ds_0.merchant_users",
                "ds_0.merchants",
                "ds_0.organization_users",
                "ds_0.organizations",
                "ds_0.outbox_events",
                "ds_0.payment_links",
                "ds_0.payment_tokens",
                "ds_0.processed_webhook_events",
                "ds_0.provider_accounts",
                "ds_0.refresh_tokens",
                "ds_0.refunds",
                "ds_0.routing_rules",
                "ds_0.users",
                "ds_0.webhook_deliveries",
                "ds_0.webhook_endpoints"
        );
    }

    private Map<String, AlgorithmConfiguration> shardingAlgorithms() {
        Map<String, AlgorithmConfiguration> algorithms = new LinkedHashMap<>();
        algorithms.put("payment_intent_inline", inlineAlgorithm("payment_intents", "id"));
        algorithms.put("payment_request_inline", inlineAlgorithm("payment_requests", "payment_intent_id"));
        return algorithms;
    }

    private AlgorithmConfiguration inlineAlgorithm(String tableName, String columnName) {
        Properties props = new Properties();
        props.setProperty("algorithm-expression",
                tableName + "_${String.format(\"%02d\", Math.floorMod(" + columnName + ".hashCode(), 64))}");
        return new AlgorithmConfiguration("INLINE", props);
    }

    private String actualDataNodes(String tableName) {
        Collection<String> nodes = new ArrayList<>(PAYMENT_SHARD_COUNT);
        for (int i = 0; i < PAYMENT_SHARD_COUNT; i++) {
            nodes.add("ds_0." + tableName + "_" + String.format("%02d", i));
        }
        return String.join(",", nodes);
    }
}
