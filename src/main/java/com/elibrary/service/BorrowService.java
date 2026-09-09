package com.elibrary.service;

import com.elibrary.cache.BorrowScriptService;
import com.elibrary.cache.BorrowedSetLoader;
import com.elibrary.cache.BookCacheService;
import com.elibrary.cache.StockLoader;
import com.elibrary.common.BizException;
import com.elibrary.config.AppProperties;
import com.elibrary.domain.Book;
import com.elibrary.domain.BorrowRecord;
import com.elibrary.domain.enums.BorrowStatus;
import com.elibrary.dto.response.BorrowResult;
import com.elibrary.dto.response.BorrowingItem;
import com.elibrary.dto.response.ReturnResult;
import com.elibrary.repository.BookMapper;
import com.elibrary.repository.BorrowRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.elibrary.common.ErrorCode.ALREADY_BORROWED;
import static com.elibrary.common.ErrorCode.BOOK_NOT_FOUND;
import static com.elibrary.common.ErrorCode.NOT_BORROWED;
import static com.elibrary.common.ErrorCode.NO_AVAILABLE_COPIES;

/**
 * 借阅/归还业务编排(设计文档 §8.1/§8.2):
 * <p>
 * 借阅 = Redis Lua 原子预扣 → DB 无锁落库(条件 UPDATE + 唯一索引)→ 失败补偿
 * 归还 = Redis Lua 原子回补 → DB 无锁落库(条件 UPDATE)→ 失败补偿
 * <p>
 * 并发正确性三层防线:
 * 1. Redis Lua:内存原子拦截 99% 的并发冲突(无库存/重复借),不碰 DB 锁;
 * 2. DB 条件写入:UPDATE ... WHERE available_copies > 0 / status='BORROWED',锁持有时间为单条语句;
 * 3. DB 唯一索引:uk_user_book_active,不变量最终防线。
 * <p>
 * Redis 故障时自动降级纯 DB 路径(§8.3),正确性不变,仅性能回落。
 * <p>
 * 说明:接口不提供幂等键,客户端重试会自然收到业务错误(40902/40903),
 * 且不会产生任何副作用(重复借阅被唯一索引/已借集合拦截,重复归还被条件更新拦截)。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BorrowService {

    private final BorrowScriptService scripts;
    private final StockLoader stockLoader;
    private final BorrowedSetLoader borrowedSetLoader;
    private final BookMapper bookMapper;
    private final BorrowRecordMapper recordMapper;
    private final BookCacheService bookCache;
    private final StringRedisTemplate redis;
    private final AppProperties props;
    private final TransactionTemplate transactionTemplate;

    // ==================== 借阅 ====================

    public BorrowResult borrow(long userId, long bookId) {
        Book book = bookMapper.selectById(bookId);
        if (book == null) {
            throw new BizException(BOOK_NOT_FOUND);
        }

        // 第一步:Redis Lua 原子预扣(并发主战场)
        boolean preDeducted = false;
        try {
            BorrowScriptService.BorrowResult scriptResult = scripts.borrow(bookId, userId);
            if (scriptResult == BorrowScriptService.BorrowResult.NOT_INIT) {
                // 库存 key 缺失:懒加载自愈后重试一次
                if (stockLoader.ensureStock(bookId)) {
                    scriptResult = scripts.borrow(bookId, userId);
                }
            }
            switch (scriptResult) {
                case OK -> preDeducted = true;
                case NO_STOCK -> throw new BizException(NO_AVAILABLE_COPIES);
                case ALREADY -> throw new BizException(ALREADY_BORROWED);
                case NOT_INIT -> log.warn("库存懒加载失败,降级 DB 路径: bookId={}", bookId);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // Redis 连接异常:预扣结果未知,降级 DB 路径(DB 约束兜底,漂移由对账修复)
            log.warn("Redis 预扣异常,降级 DB 路径: bookId={}, userId={}", bookId, userId, e);
            preDeducted = false;
        }

        // 第二步:DB 无锁落库(乐观路径),失败则补偿 Redis 预扣
        try {
            BorrowResult result = transactionTemplate.execute(status -> writeBorrow(userId, bookId, book));
            bookCache.deleteDetail(bookId); // 详情含可借数,写后失效
            return result;
        } catch (DuplicateKeyException e) {
            // uk_user_book_active:同一用户对同一书已有未归还记录
            if (preDeducted) {
                compensateBorrow(bookId, userId);
            }
            throw new BizException(ALREADY_BORROWED);
        } catch (RuntimeException e) {
            if (preDeducted) {
                compensateBorrow(bookId, userId);
            }
            throw e;
        }
    }

    private BorrowResult writeBorrow(long userId, long bookId, Book book) {
        LocalDateTime now = LocalDateTime.now();
        BorrowRecord record = new BorrowRecord();
        record.setUserId(userId);
        record.setBookId(bookId);
        record.setStatus(BorrowStatus.BORROWED);
        record.setActiveFlag(1);
        record.setBorrowedAt(now);
        record.setDueAt(now.plusDays(props.getBorrowDays()));
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        recordMapper.insert(record);

        // 不变量 I1 的 DB 兜底:影响行数为 0 说明并发下已无副本,事务回滚
        if (bookMapper.decrementAvailable(bookId) == 0) {
            throw new BizException(NO_AVAILABLE_COPIES);
        }
        return toBorrowResult(record, book);
    }

    // ==================== 归还 ====================

    public ReturnResult returnBook(long userId, long bookId) {
        // 第一步:Redis Lua 原子回补
        boolean redisReturned = false;
        try {
            BorrowScriptService.ReturnResult scriptResult = scripts.returnBook(bookId, userId);
            if (scriptResult == BorrowScriptService.ReturnResult.NOT_INIT) {
                // 库存 key 缺失:懒加载自愈后重试一次
                stockLoader.ensureStock(bookId);
                borrowedSetLoader.ensure(userId);
                scriptResult = scripts.returnBook(bookId, userId);
            }
            if (scriptResult == BorrowScriptService.ReturnResult.NOT_BORROWED && !isBorrowedSetInitialized(userId)) {
                // 已借集合丢失自愈:重建后再试一次
                borrowedSetLoader.ensure(userId);
                scriptResult = scripts.returnBook(bookId, userId);
            }
            switch (scriptResult) {
                case OK -> redisReturned = true;
                case NOT_BORROWED -> throw new BizException(NOT_BORROWED);
                case NOT_INIT -> log.warn("Redis 初始化失败,降级 DB 路径: bookId={}", bookId);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis 归还异常,降级 DB 路径: bookId={}, userId={}", bookId, userId, e);
            redisReturned = false;
        }

        // 第二步:DB 无锁落库,失败则补偿 Redis
        try {
            ReturnResult result = transactionTemplate.execute(status -> writeReturn(userId, bookId));
            bookCache.deleteDetail(bookId);
            return result;
        } catch (RuntimeException e) {
            if (redisReturned) {
                compensateReturn(bookId, userId);
            }
            throw e;
        }
    }

    private ReturnResult writeReturn(long userId, long bookId) {
        // 不变量 I3 的 DB 兜底:条件更新,仅"借阅中"的记录可被归还
        if (recordMapper.markReturned(userId, bookId) == 0) {
            throw new BizException(NOT_BORROWED);
        }
        BorrowRecord record = recordMapper.findLatestByUserAndBook(userId, bookId);
        bookMapper.incrementAvailable(bookId);
        return toReturnResult(record);
    }

    private ReturnResult toReturnResult(BorrowRecord borrowRecord) {
        return new ReturnResult(borrowRecord.getId(), null, null, borrowRecord.getReturnedAt());
    }

    // ==================== 我的借阅 ====================

    /**
     * 我的借阅列表。
     * 读路径直查 DB(idx_record_user_status 索引,每用户数据量小,毫秒级);
     * Redis 已借集合仅服务于借/还并发控制,不作为读数据源 —— 避免集合与 DB
     * 短暂不一致(补偿失败/降级窗口)时读到错误结果。
     */
    public List<BorrowingItem> myBorrowings(long userId, BorrowStatus status) {
        List<BorrowRecord> records = recordMapper.selectByUserAndStatus(userId, status);
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> bookIds = records.stream().map(BorrowRecord::getBookId).distinct().toList();
        Map<Long, Book> books = bookMapper.selectBatchIds(bookIds).stream()
                .collect(Collectors.toMap(Book::getId, Function.identity()));
        LocalDateTime now = LocalDateTime.now();
        return records.stream()
                .map(record -> toBorrowingItem(record, books.get(record.getBookId()), now))
                .toList();
    }

    // ==================== 补偿 ====================

    private void compensateBorrow(long bookId, long userId) {
        try {
            scripts.undoBorrow(bookId, userId);
        } catch (Exception e) {
            // 补偿失败:不变量由 DB 约束兜底,计数漂移由对账任务修复(生产扩展点)
            log.error("借阅补偿失败,需对账修复: bookId={}, userId={}", bookId, userId, e);
        }
    }

    private void compensateReturn(long bookId, long userId) {
        try {
            scripts.undoReturn(bookId, userId);
        } catch (Exception e) {
            log.error("归还补偿失败,需对账修复: bookId={}, userId={}", bookId, userId, e);
        }
    }

    // ==================== 辅助 ====================

    private boolean isBorrowedSetInitialized(long userId) {
        try {
            return redis.type(BorrowScriptService.borrowedKey(userId)) == DataType.SET;
        } catch (Exception e) {
            return true; // 无法确认时按已初始化处理,交给 DB 条件更新兜底
        }
    }

    private static BorrowResult toBorrowResult(BorrowRecord record, Book book) {
        return new BorrowResult(record.getId(), record.getBookId(),
                book == null ? null : book.getTitle(), record.getBorrowedAt(), record.getDueAt());
    }

    private static ReturnResult toReturnResult(BorrowRecord record, Book book) {
        return new ReturnResult(record.getId(), record.getBookId(),
                book == null ? null : book.getTitle(), record.getReturnedAt());
    }

    private static BorrowingItem toBorrowingItem(BorrowRecord record, Book book, LocalDateTime now) {
        return new BorrowingItem(record.getId(), record.getBookId(),
                book == null ? null : book.getTitle(),
                record.getBorrowedAt(), record.getDueAt(), record.isOverdue(now));
    }
}
