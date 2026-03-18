package com.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    // Reactive template (for Gateway filters)
    @Bean
    public ReactiveRedisTemplate<String, String> reactiveRedisTemplate(
            ReactiveRedisConnectionFactory factory) {

        RedisSerializationContext<String, String> context =
                RedisSerializationContext.<String, String>newSerializationContext()
                        .key(StringRedisSerializer.UTF_8)
                        .value(StringRedisSerializer.UTF_8)
                        .hashKey(StringRedisSerializer.UTF_8)
                        .hashValue(StringRedisSerializer.UTF_8)
                        .build();

        return new ReactiveRedisTemplate<>(factory, context);
    }

    // Blocking template (for auth service)
    @Bean
    public RedisTemplate<String, String> redisTemplate(
            org.springframework.data.redis.connection.RedisConnectionFactory factory) {

        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}