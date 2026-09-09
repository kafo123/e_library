package com.elibrary.controller;

import com.elibrary.support.EmbeddedRedisTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API 全链路测试:状态码、统一响应结构、错误码、请求头校验。
 */
@AutoConfigureMockMvc
class ApiIntegrationTest extends EmbeddedRedisTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void 浏览书籍分页() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("page", "1").param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(3))
                .andExpect(jsonPath("$.data.total").value(8))
                .andExpect(jsonPath("$.data.pages").value(3));
    }

    @Test
    void 浏览书籍关键字过滤() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("keyword", "Java").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void 浏览书籍类型过滤() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("type", "JOURNAL").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void 非法类型参数被拒绝() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("type", "AUDIOBOOK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void 分页大小超过上限被拒绝() throws Exception {
        mockMvc.perform(get("/api/v1/books").param("size", "200"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void 书籍详情() throws Exception {
        mockMvc.perform(get("/api/v1/books/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.title").value("Effective Java"))
                .andExpect(jsonPath("$.data.availableCopies").value(3));
    }

    @Test
    void 书籍不存在返回404() throws Exception {
        mockMvc.perform(get("/api/v1/books/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40401));
    }

    @Test
    void 借阅缺少用户头被拒绝() throws Exception {
        mockMvc.perform(post("/api/v1/books/1/borrow"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40002));
    }

    @Test
    void 借阅成功且详情可借数同步扣减() throws Exception {
        mockMvc.perform(post("/api/v1/books/1/borrow").header("X-User-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.bookId").value(1))
                .andExpect(jsonPath("$.data.dueAt").exists());

        // 详情可借数同步扣减:3 -> 2
        mockMvc.perform(get("/api/v1/books/1"))
                .andExpect(jsonPath("$.data.availableCopies").value(2));
    }

    @Test
    void 重复借阅返回40902且无副作用_重试场景() throws Exception {
        mockMvc.perform(post("/api/v1/books/1/borrow").header("X-User-Id", "1"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/books/1/borrow").header("X-User-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40902));

        // 库存未被二次扣减
        mockMvc.perform(get("/api/v1/books/1"))
                .andExpect(jsonPath("$.data.availableCopies").value(2));
    }

    @Test
    void 借阅无库存返回40901() throws Exception {
        // 书 8 仅 1 副本:用户 1 借走
        mockMvc.perform(post("/api/v1/books/8/borrow").header("X-User-Id", "1"))
                .andExpect(status().isOk());
        // 用户 2 再借 → 40901
        mockMvc.perform(post("/api/v1/books/8/borrow").header("X-User-Id", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40901));
    }

    @Test
    void 归还未借过的书返回40903() throws Exception {
        mockMvc.perform(post("/api/v1/books/5/return").header("X-User-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40903));
    }

    @Test
    void 我的借阅列表() throws Exception {
        mockMvc.perform(get("/api/v1/borrowings")
                        .header("X-User-Id", "1")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void 非法状态参数被拒绝() throws Exception {
        mockMvc.perform(get("/api/v1/borrowings")
                        .header("X-User-Id", "1")
                        .param("status", "XXX"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }
}
