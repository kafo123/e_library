package com.elibrary.support;

import redis.embedded.RedisServer;

/**
 * 内嵌真实 redis-server 进程(整个测试 JVM 共享一个实例)。
 * 并发/幂等/补偿测试必须打真实 Redis 链路:Redis 的 Lua 脚本与
 * 单线程原子模型无法用 mock 替代,故测试内启动真实 redis-server。
 */
public final class EmbeddedRedisHolder {

    private static final RedisServer SERVER = start();

    private EmbeddedRedisHolder() {
    }

    private static RedisServer start() {
        RedisServer server = new RedisServer(0); // 随机可用端口
        server.start();
        return server;
    }

    public static int port() {
        return SERVER.getPort();
    }
}
