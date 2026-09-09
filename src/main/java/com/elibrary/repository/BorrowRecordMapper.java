package com.elibrary.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.elibrary.domain.BorrowRecord;
import com.elibrary.domain.enums.BorrowStatus;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface BorrowRecordMapper extends BaseMapper<BorrowRecord> {

    /**
     * 用户对某书的最新一条记录(归还校验用)
     */
    @Select("SELECT * FROM borrow_record WHERE user_id = #{userId} AND book_id = #{bookId} ORDER BY id DESC LIMIT 1")
    BorrowRecord findLatestByUserAndBook(@Param("userId") long userId, @Param("bookId") long bookId);

    /**
     * 按用户 + 状态查借阅记录(我的借阅)
     */
    @Select("SELECT * FROM borrow_record WHERE user_id = #{userId} AND status = #{status} ORDER BY borrowed_at DESC")
    List<BorrowRecord> selectByUserAndStatus(@Param("userId") long userId, @Param("status") BorrowStatus status);

    /**
     * 用户当前借阅中的 bookId 列表(Redis 已借集合懒重建数据源)
     */
    @Select("SELECT book_id FROM borrow_record WHERE user_id = #{userId} AND status = 'BORROWED'")
    List<Long> selectActiveBookIdsByUser(@Param("userId") long userId);

    /**
     * 条件归还(不变量 I3 的 DB 兜底):
     * 仅当"借阅中"时更新成功,影响行数为 0 表示无可还记录(40903)。
     */
    @Update("UPDATE borrow_record SET status = 'RETURNED', returned_at = NOW(), active_flag = NULL, " +
            "updated_at = NOW() " +
            "WHERE user_id = #{userId} AND book_id = #{bookId} AND status = 'BORROWED'")
    int markReturned(@Param("userId") long userId, @Param("bookId") long bookId);
}
