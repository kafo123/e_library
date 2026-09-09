package com.elibrary.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 借/还 Lua 脚本执行器:Redis 并发控制主战场(设计文档 §8.1/§8.2)。
 * 每个脚本一次网络往返完成"检查 + 变更",天然原子,无锁等待。
 */
@Component
@Slf4j
public class BorrowScriptService {

    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> borrowScript;
    private final DefaultRedisScript<Long> returnScript;
    private final DefaultRedisScript<Long> undoBorrowScript;
    private final DefaultRedisScript<Long> undoReturnScript;

    public BorrowScriptService(StringRedisTemplate redis,
                               @Qualifier("borrowScript") DefaultRedisScript<Long> borrowScript,
                               @Qualifier("returnScript") DefaultRedisScript<Long> returnScript,
                               @Qualifier("undoBorrowScript") DefaultRedisScript<Long> undoBorrowScript,
                               @Qualifier("undoReturnScript") DefaultRedisScript<Long> undoReturnScript) {
        this.redis = redis;
        this.borrowScript = borrowScript;
        this.returnScript = returnScript;
        this.undoBorrowScript = undoBorrowScript;
        this.undoReturnScript = undoReturnScript;
    }

    public enum BorrowResult { OK, NO_STOCK, ALREADY, NOT_INIT }

    public enum ReturnResult { OK, NOT_BORROWED, NOT_INIT }

    /** 借阅预扣(原子) */
    public BorrowResult borrow(long bookId, long userId) {
        Long r = execute(borrowScript, keys(bookId, userId), String.valueOf(bookId));
        return switch (toInt(r)) {
            case 0 -> BorrowResult.OK;
            case 1 -> BorrowResult.NO_STOCK;
            case 2 -> BorrowResult.ALREADY;
            default -> BorrowResult.NOT_INIT;
        };
    }

    /** 归还回补(原子) */
    public ReturnResult returnBook(long bookId, long userId) {
        Long r = execute(returnScript, keys(bookId, userId), String.valueOf(bookId));
        return switch (toInt(r)) {
            case 0 -> ReturnResult.OK;
            case 3 -> ReturnResult.NOT_BORROWED;
            default -> ReturnResult.NOT_INIT;
        };
    }

    /** 借阅补偿:DB 落库失败后回补预扣(脚本内带并发交错守卫) */
    public void undoBorrow(long bookId, long userId) {
        execute(undoBorrowScript, keys(bookId, userId), String.valueOf(bookId));
    }

    /** 归还补偿:DB 落库失败后回补(脚本内带并发交错守卫) */
    public void undoReturn(long bookId, long userId) {
        execute(undoReturnScript, keys(bookId, userId), String.valueOf(bookId));
    }

    private Long execute(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        return redis.execute(script, keys, (Object[]) args);
    }

    private List<String> keys(long bookId, long userId) {
        return List.of(stockKey(bookId), borrowedKey(userId));
    }

    private static int toInt(Long v) {
        return v == null ? -1 : v.intValue();
    }

    public static String stockKey(long bookId) {
        return "lib:book:" + bookId + ":stock";
    }

    public static String borrowedKey(long userId) {
        return "lib:user:" + userId + ":borrowed";
    }
}
