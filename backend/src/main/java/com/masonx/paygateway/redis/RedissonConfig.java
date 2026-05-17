package com.masonx.paygateway.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${spring.data.redis.url:}") String url,
                                         @Value("${spring.data.redis.host:localhost}") String host,
                                         @Value("${spring.data.redis.port:6379}") int port,
                                         @Value("${spring.data.redis.password:}") String password) {
        Config config = new Config();
        String address = redisAddress(url, host, port);
        var singleServer = config.useSingleServer()
                .setAddress(address)
                .setTimeout(200)
                .setRetryAttempts(1)
                .setRetryInterval(100);
        if (password != null && !password.isBlank()) {
            singleServer.setPassword(password);
        }
        return Redisson.create(config);
    }

    private String redisAddress(String url, String host, int port) {
        if (url != null && !url.isBlank()) {
            if (url.startsWith("redis://") || url.startsWith("rediss://")) {
                return url;
            }
            return "redis://" + url;
        }
        return "redis://" + host + ":" + port;
    }
}
