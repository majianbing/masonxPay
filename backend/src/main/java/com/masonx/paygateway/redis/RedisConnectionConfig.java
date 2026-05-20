package com.masonx.paygateway.redis;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@Configuration
public class RedisConnectionConfig {

    @Bean
    @ConditionalOnExpression("'${REDIS_URL:}' != ''")
    public LettuceConnectionFactory redisConnectionFactory(@Value("${REDIS_URL}") String redisUrl) {
        URI uri = URI.create(normalize(redisUrl));
        RedisStandaloneConfiguration redis = new RedisStandaloneConfiguration(uri.getHost(), redisPort(uri));
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            String[] credentials = userInfo.split(":", 2);
            if (!credentials[0].isBlank()) {
                redis.setUsername(credentials[0]);
            }
            if (credentials.length > 1 && !credentials[1].isBlank()) {
                redis.setPassword(RedisPassword.of(credentials[1]));
            }
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            redis.setDatabase(Integer.parseInt(path.substring(1)));
        }

        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(200));
        if ("rediss".equalsIgnoreCase(uri.getScheme())) {
            client.useSsl();
        }
        return new LettuceConnectionFactory(redis, client.build());
    }

    private int redisPort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "rediss".equalsIgnoreCase(uri.getScheme()) ? 6380 : 6379;
    }

    private String normalize(String redisUrl) {
        if (redisUrl.startsWith("redis://") || redisUrl.startsWith("rediss://")) {
            return redisUrl;
        }
        return "redis://" + redisUrl;
    }
}
