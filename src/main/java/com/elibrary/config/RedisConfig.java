package com.elibrary.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Redis 配置:Lua 脚本注册为 Bean,脚本与代码同仓库管理(lua/*.lua)
 */
@Configuration
public class RedisConfig {

    private static DefaultRedisScript<Long> script(String location) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(location));
        script.setResultType(Long.class);
        return script;
    }

    /** 借阅预扣(原子:查库存 + 查已借 + 扣减 + 登记) */
    @Bean("borrowScript")
    public DefaultRedisScript<Long> borrowScript() {
        return script("lua/borrow.lua");
    }

    /** 归还回补(原子:查已借 + 移除 + 回补) */
    @Bean("returnScript")
    public DefaultRedisScript<Long> returnScript() {
        return script("lua/return.lua");
    }

    /** 借阅补偿(DB 落库失败后回补,带并发交错守卫) */
    @Bean("undoBorrowScript")
    public DefaultRedisScript<Long> undoBorrowScript() {
        return script("lua/undo_borrow.lua");
    }

    /** 归还补偿(DB 落库失败后回补,带并发交错守卫) */
    @Bean("undoReturnScript")
    public DefaultRedisScript<Long> undoReturnScript() {
        return script("lua/undo_return.lua");
    }
}
