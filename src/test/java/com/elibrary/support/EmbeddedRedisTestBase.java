package com.elibrary.support;

import com.elibrary.domain.Book;
import com.elibrary.repository.BookMapper;
import com.elibrary.repository.BorrowRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Objects;

/**
 * 集成测试基类:内嵌真实 Redis + MySQL(独立测试库 e_library_test)。
 * 每个用例前重置状态:清空 Redis、清空借阅记录、恢复可借副本数。
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class EmbeddedRedisTestBase {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.port", EmbeddedRedisHolder::port);
    }

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected BookMapper bookMapper;

    @Autowired
    protected BorrowRecordMapper recordMapper;

    @BeforeEach
    void cleanState() {
        // Redis:清空所有 key(库存计数/已借集合/详情与分页缓存)
        Objects.requireNonNull(redis.getConnectionFactory()).getConnection().serverCommands().flushAll();
        // DB:清空借阅记录,恢复可借副本
        recordMapper.delete(null);
        for (Book book : bookMapper.selectList(null)) {
            bookMapper.resetAvailable(book.getId());
        }
    }
}
