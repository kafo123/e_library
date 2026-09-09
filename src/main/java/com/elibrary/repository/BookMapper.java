package com.elibrary.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.elibrary.domain.Book;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface BookMapper extends BaseMapper<Book> {

    /**
     * 条件扣减可借副本(不变量 I1 的 DB 兜底):
     * WHERE available_copies > 0 保证并发下不会扣成负数,影响行数为 0 表示无可用副本。
     * 无行锁,锁持有时间仅为单条 UPDATE 的执行瞬间。
     */
    @Update("UPDATE book SET available_copies = available_copies - 1, updated_at = NOW() " +
            "WHERE id = #{id} AND available_copies > 0")
    int decrementAvailable(@Param("id") long id);

    /**
     * 归还回补(加法无超发风险,无需守卫条件)
     */
    @Update("UPDATE book SET available_copies = available_copies + 1, updated_at = NOW() WHERE id = #{id}")
    int incrementAvailable(@Param("id") long id);

    /**
     * 测试辅助:重置可借副本为总副本数
     */
    @Update("UPDATE book SET available_copies = total_copies, updated_at = NOW() WHERE id = #{id}")
    int resetAvailable(@Param("id") long id);
}
