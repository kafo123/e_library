package com.elibrary.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务配置(application.yml 中 library.* 前缀)
 */
@Data
@ConfigurationProperties(prefix = "library")
public class AppProperties {

    /** 借阅期限(天) */
    private int borrowDays = 14;

    /** Redis 故障后进入降级路径的窗口时长(秒) */
    private long redisUnhealthySeconds = 10;

    private Cache cache = new Cache();

    @Data
    public static class Cache {
        private long bookDetailTtlSeconds = 1800;
        private long bookDetailJitterSeconds = 300;
        private long browseTtlSeconds = 300;
        private long nullValueTtlSeconds = 60;
    }
}
