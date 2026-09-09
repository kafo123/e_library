# 电子图书馆服务(E-Library Service)设计文档

> 对应需求文档:`Backend_Take_Home_E_Library_Service Chinese(1).docx`
> 技术栈:Java 17 + Spring Boot 3.x + MyBatis(-Plus) + MySQL 8 + Redis 7
>
> | 版本 | 日期 | 变更 |
> |------|------|------|
> | v1.0 | 2026-09-09 | 初版:DB 行锁(`FOR UPDATE`)保证并发 |
> | v1.1 | 2026-09-09 | 并发方案优化:Redis Lua 原子预扣减,DB 降级为落库 + 最终防线,消除事务级行锁 |
> | v1.2 | 2026-09-09 | (历史)增加借/还接口幂等性保证:幂等键三层架构 |
> | v1.3 | 2026-09-09 | 实现校准:F5 读路径改 DB 直查、Lua 返回码改整数、表名 users、测试用内嵌真实 Redis 进程 |
> | v1.4 | 2026-09-09 | 测试数据库由 H2 改为 MySQL(独立测试库 e_library_test,可环境变量覆盖) |
> | **v1.5** | 2026-09-09 | **撤销幂等键设计(评审意见:超出作业范围,有点多余);重试由业务规则自然拒绝且无副作用,幂等键列入演进方向** |

---

## 1. 需求分析

### 1.1 功能需求

| # | 需求 | 说明 |
|---|------|------|
| F1 | 浏览书籍 | 分页浏览电子书/期刊,支持关键字、类型、分类过滤与排序 |
| F2 | 查询书籍详细资料 | 单本书的完整信息(含当前可借数量) |
| F3 | 借阅书籍 | 用户借阅一本书,扣减可借数量,生成借阅记录与到期时间 |
| F4 | 归还书籍 | 归还已借阅的书,恢复可借数量,记录归还时间 |
| F5 | 查看当前已借阅书籍 | 列出当前用户所有未归还的借阅记录(含是否逾期) |

### 1.2 非功能需求(作业考察点)

- **应用架构**:分层清晰、职责单一、可测试
- **领域建模**:实体、值对象、枚举、业务不变量(invariants)显式表达
- **API 设计**:RESTful 风格、统一的响应结构与错误码
- **代码组织**:包结构合理,DTO / Entity 分离
- **错误处理**:业务异常体系 + 全局处理 + 参数校验
- **可维护性**:可读性优先、配置外置、关键设计有测试守护
- **范围控制**:题目明确要求"控制实现范围在合理程度",**不过度设计**

---

## 2. 关键假设(题目允许合理假设,逐条声明)

| # | 假设 | 理由 |
|---|------|------|
| A1 | **认证鉴权不在范围内**。用户通过请求头 `X-User-Id` 标识,用户数据预置 | 题目未要求注册/登录,自建认证会显著扩大范围 |
| A2 | 借阅期限为 **14 天**,通过配置项 `library.borrow-days` 可调 | 题目未定义,取常见默认值 |
| A3 | 采用**馆藏副本模型**:`total_copies` / `available_copies` 冗余计数 | 数字内容借阅常见模型;计数一致性由并发方案保证 |
| A4 | **同一用户对同一本书同时只能持有一条未归还借阅记录**;归还后历史记录保留,允许再次借阅 | 符合图书馆常识;由 DB 唯一约束兜底 |
| A5 | 不支持续借、预约、逾期罚款(超范围);逾期为**读时派生状态**,不做后台任务 | 题目只要求"借/还/查",派生状态零成本覆盖逾期展示 |
| A6 | 电子书与期刊用**同一张 `book` 表 + `type` 判别字段**,期刊特有的卷期信息暂不建模 | 单表足够覆盖题目范围;分表建模见 §11 取舍 |
| A7 | 不做书籍/用户的增删改管理接口,种子数据通过 `data.sql` 预置 | 题目无管理端需求 |
| A8 | 分页默认 `page=1, size=20`,上限 `size<=100` | 防大页拖垮数据库的常规防御 |
| A9 | 归还只能针对"自己当前借阅中的书";不存在借阅记录或已归还 → 业务错误 | 题目语义("归还书籍")的自然约束 |
| A10 | 浏览列表**不展示实时可借数**(详情页展示),避免列表缓存频繁失效 | 见 §9 缓存设计,列表缓存只需 TTL 即可 |
| A11 | **允许短窗口的最终一致**:Redis 计数与 DB 在借/还成功提交后同步;异常中断场景靠补偿 + DB 兜底修复 | 用极小的一致性代价换取 DB 锁压力的大幅下降,是本方案的核心取舍 |
| A12 | **不提供幂等键**(v1.5 撤销 v1.2 设计):客户端重试/重复提交收到业务错误(40902/40903),且**保证无副作用** | 幂等键引入 PENDING 状态机与幂等列,超出作业范围(评审意见);业务规则的天然拦截已保证安全,幂等键列入演进方向(§11 T13) |

---

## 3. 总体架构

单体应用 + 分层架构。并发控制主体在 Redis,DB 为持久化事实源与最终防线。

```
                    ┌──────────────────────────────────────────────────────┐
                    │                  Spring Boot 单体                     │
  ┌──────────┐      │  ┌────────────┐   ┌────────────────────────────────┐  │
  │  Client  │ ───▶ │  │ Controller │──▶│ Service(业务编排)               │  │
  │(Postman/ │      │  │ (REST/校验) │   │ BorrowService / ReturnService │  │
  │ Swagger) │      │  └────────────┘   └───┬──────────────┬────────────┘  │
  └──────────┘      │                       ▼              ▼               │
                    │          ┌──────────────────────┐  ┌──────────────┐  │
                    │          │ Redis 并发层(主路径)  │  │ Mapper 落库  │  │
                    │          │ · Lua 原子预扣/回补   │  │ (MyBatis,    │  │
                    │          │ · 库存 key / 已借集合 │  │  条件写入,   │  │
                    │          │ · 详情/列表读缓存     │  │  无行锁)     │  │
                    │          └──────────┬───────────┘  └──────┬───────┘  │
                    └─────────────────────┼──────────────────────┼─────────┘
                                          ▼                      ▼
                                    ┌──────────┐          ┌──────────┐
                                    │  Redis 7 │          │  MySQL 8 │
                                    │(并发主战场)│          │(事实源)  │
                                    └──────────┘          └──────────┘
```

- **Redis = 并发控制主战场**:库存计数、用户已借集合、Lua 原子脚本,借/还的并发冲突在内存中 O(1) 解决,**不触碰 DB 锁**
- **MySQL = 持久化事实源 + 最终防线**:借阅记录落库、条件 UPDATE、唯一索引,即使 Redis 完全失效,DB 也能独立保证不变量不被破坏
- **依赖方向**:Controller → Service → Redis 脚本层 / Mapper,单向

---

## 4. 技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| 语言 / 构建 | Java 17 + Maven | LTS,语法现代(record/sealed),公司常见基线 |
| Web 框架 | Spring Boot 3.x + spring-boot-starter-web | 生态成熟;内嵌 Tomcat 零配置 |
| ORM | MyBatis-Plus 3.5.x(本质仍是 MyBatis) | 题目指定 MyBatis;Plus 提供 CRUD/分页基建,关键 SQL(条件更新/唯一索引)手写 |
| 数据库 | MySQL 8 | 唯一索引 + 条件 UPDATE 承担最终正确性兜底 |
| 缓存 / 并发 | spring-boot-starter-data-redis(Lettuce)+ **Lua 脚本**(`DefaultRedisScript`) | 单次往返完成"检查+扣减+登记",原子且无锁等待;无需引入 Redisson |
| API 文档 | springdoc-openapi(Swagger UI) | 零成本交互式文档 |
| 校验 | jakarta.validation | `@Valid` 声明式参数校验 |
| 简化样板 | Lombok | 实体/DTO 去样板代码 |
| 测试 | JUnit 5 + Mockito + MySQL(独立测试库 e_library_test)+ MockMvc + 内嵌真实 Redis 进程 | 并发测试必须打真实 Redis 链路 |

> 版本说明:Spring Boot 采用成熟的 3.x 系(生态兼容性优先),具体以脚手架生成时的稳定版为准。

---

## 5. 领域模型

### 5.1 实体

```
Book(书籍/期刊)
  - id: Long
  - title: String            # 书名/刊名
  - author: String           # 作者/出版方
  - isbn: String(unique)
  - type: BookType           # EBOOK | JOURNAL
  - category: String         # 分类(简单字符串字段,不做分类表)
  - description: String
  - totalCopies: int         # 馆藏总副本
  - availableCopies: int     # 当前可借副本(冗余计数,条件 UPDATE 维护)
  - createdAt / updatedAt

User(读者)
  - id: Long
  - name: String
  - email: String

BorrowRecord(借阅记录,含领域方法)
  - id: Long
  - userId: Long
  - bookId: Long
  - borrowedAt: LocalDateTime
  - dueAt: LocalDateTime
  - returnedAt: LocalDateTime(nullable)
  - status: BorrowStatus     # BORROWED | RETURNED
  - isOverdue(now): boolean  # 领域行为:status==BORROWED && now > dueAt
```

### 5.2 业务不变量(Invariants)与三层防线

| # | 不变量 | 第一层:Redis Lua(快路径,拦截 99% 冲突) | 第二层:DB 条件写入(落库校验) | 第三层:DB 约束(最终防线) |
|---|--------|------------------------------------------|------------------------------|--------------------------|
| I1 | 可借副本 > 0 才能借出 | Lua 检查 `stock > 0` 才 `DECR`,原子 | `UPDATE book SET available_copies = available_copies - 1 WHERE id=? AND available_copies > 0`,影响行数 =0 则拒绝 | — |
| I2 | 同一用户对同一书至多一条 BORROWED 记录 | Lua 检查 `SISMEMBER` 已借集合,原子 | INSERT 前服务层校验 | 唯一索引 `uk_user_book_active` 兜底(见 §6) |
| I3 | 只能归还自己借阅中且未还的书 | Lua 检查 `SISMEMBER`,不存在即拒绝,原子 | `UPDATE borrow_record SET status='RETURNED' ... WHERE ... AND status='BORROWED'`,影响行数 =0 则拒绝 | — |
| I4 | 归还后副本数不能超过总副本 | Lua `INCR` 上限校验(可与 totalCopies 比较) | 只对 BORROWED→RETURNED 加回 | — |

**设计核心思想**:并发冲突(无库存、重复借)在 Redis 内存层被原子拦截,**根本不会打到 DB**;DB 侧只做无锁的条件写入与唯一约束兜底,锁持有时间从"整个事务"降为"单条语句瞬间"。

---

## 6. 数据库设计

### 6.1 DDL(MySQL 8)

```sql
CREATE TABLE `users` (
  `id`         BIGINT AUTO_INCREMENT PRIMARY KEY,
  `name`       VARCHAR(64)  NOT NULL,
  `email`      VARCHAR(128) NULL,
  `created_at` DATETIME NOT NULL
) ENGINE=InnoDB;

CREATE TABLE `book` (
  `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
  `title`            VARCHAR(255) NOT NULL,
  `author`           VARCHAR(128) NOT NULL,
  `isbn`             VARCHAR(32)  NULL,
  `type`             VARCHAR(16)  NOT NULL COMMENT 'EBOOK / JOURNAL',
  `category`         VARCHAR(64)  NULL,
  `description`      TEXT         NULL,
  `total_copies`     INT NOT NULL,
  `available_copies` INT NOT NULL,
  `created_at`       DATETIME NOT NULL,
  `updated_at`       DATETIME NOT NULL,
  UNIQUE KEY `uk_isbn` (`isbn`),
  KEY `idx_type_category` (`type`, `category`)
) ENGINE=InnoDB;

CREATE TABLE `borrow_record` (
  `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
  `user_id`     BIGINT NOT NULL,
  `book_id`     BIGINT NOT NULL,
  `borrowed_at` DATETIME NOT NULL,
  `due_at`      DATETIME NOT NULL,
  `returned_at` DATETIME NULL,
  `status`      VARCHAR(16) NOT NULL COMMENT 'BORROWED / RETURNED',
  `active_flag` TINYINT NULL COMMENT '借阅中=1,已归还=NULL(配合唯一索引防重复借阅)',
  `created_at`  DATETIME NOT NULL,
  `updated_at`  DATETIME NOT NULL,
  UNIQUE KEY `uk_user_book_active` (`user_id`, `book_id`, `active_flag`),
  KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB;
```

关键设计说明:

1. **`uk_user_book_active` 唯一索引**:借阅中 `active_flag=1`,归还时置 `NULL`。MySQL 唯一索引允许多个 NULL,因此同一 (user, book) 只有一条活跃记录,历史归还记录不受约束 —— 即使 Redis 完全失效,重复借阅在此被 DB 拒绝。
2. **`available_copies` 冗余**:换取详情页 O(1) 读取;一致性由"Redis 预扣 + 条件 UPDATE + 补偿"保证。
3. 种子数据 `data.sql`:约 20 本书(电子书/期刊混合、覆盖多个分类)+ 2~3 个用户。

> 实现校准:
> - 表名 `users`(而非 `user`)—— `user` 是 PostgreSQL 等数据库保留字,避免未来跨库迁移问题
> - `created_at`/`updated_at` 不依赖 `DEFAULT`/`ON UPDATE`,由 SQL 显式赋值,行为显式可控
> - v1.5:移除 v1.2 的幂等键列(`borrow_request_id`/`return_request_id`)及唯一索引

---

## 7. API 设计

统一前缀 `/api/v1`;认证假设 A1:请求头 `X-User-Id`。

### 7.1 接口清单

| 方法 | 路径 | 说明 | 成功响应 |
|------|------|------|----------|
| GET | `/api/v1/books` | 浏览书籍(分页/过滤/排序) | 200 `PageResponse<BookSummary>` |
| GET | `/api/v1/books/{id}` | 书籍详情(含 availableCopies) | 200 `BookDetail` |
| POST | `/api/v1/books/{id}/borrow` | 借阅 | 200 `BorrowResult` |
| POST | `/api/v1/books/{id}/return` | 归还 | 200 `ReturnResult` |
| GET | `/api/v1/borrowings?status=BORROWED` | 我的借阅(默认当前借阅中,可取 BORROWED/RETURNED) | 200 `List<BorrowingItem>` |

> 借/还采用"动作式"路径(而非 `POST /borrowings` 资源式),语义直白、评审易读;资源式替代方案见 §11 取舍 T4。
> 重试语义:重复提交收到 40902/40903,**无副作用**(§11 T13)。

### 7.2 请求 / 响应示例

```http
GET /api/v1/books?page=1&size=20&type=EBOOK&keyword=java&sortBy=createdAt&order=desc
X-User-Id: 1
```

```json
{
  "code": 0, "message": "success",
  "data": {
    "page": 1, "size": 20, "total": 3, "pages": 1,
    "items": [
      { "id": 1, "title": "Effective Java", "author": "Joshua Bloch",
        "type": "EBOOK", "category": "编程", "createdAt": "2026-01-01T10:00:00" }
    ]
  }
}
```

```http
POST /api/v1/books/1/borrow
X-User-Id: 1
```

```json
{ "code": 0, "message": "success",
  "data": { "borrowRecordId": 1001, "bookId": 1, "title": "Effective Java",
            "borrowedAt": "2026-09-09T16:30:00", "dueAt": "2026-09-23T16:30:00" } }
```

```http
GET /api/v1/borrowings?status=BORROWED
X-User-Id: 1
```

```json
{ "code": 0, "message": "success",
  "data": [ { "borrowRecordId": 1001, "bookId": 1, "title": "Effective Java",
              "borrowedAt": "2026-09-09T16:30:00", "dueAt": "2026-09-23T16:30:00",
              "overdue": false } ] }
```

### 7.3 统一响应结构

```json
{ "code": 0, "message": "success", "data": { ... }, "traceId": "1a2b3c4d" }
```

- `code=0` 表示成功;业务错误码见下表
- HTTP 状态码与业务码**同时有意义**:404/409/400/500 用于基础设施与网关层面的判断,`code` 用于业务分支
- `traceId` 来自 MDC 过滤器(客户端传 `X-Trace-Id` 或服务端生成),贯穿日志

### 7.4 错误码

| HTTP | code | message | 触发场景 |
|------|------|---------|----------|
| 400 | 40001 | 参数校验失败 | `@Valid` 校验、非法 type/分页参数 |
| 400 | 40002 | 缺少 X-User-Id 请求头 | 拦截器校验 |
| 404 | 40401 | 书籍不存在 | 详情/借阅时查无此书 |
| 409 | 40901 | 暂无可用副本 | Redis 预扣拒绝(I1) |
| 409 | 40902 | 您已借阅此书且未归还 | Redis 已借集合命中 / DB 唯一索引兜底(I2) |
| 409 | 40903 | 不存在可归还的借阅记录 | Redis 集合未命中 / DB 条件更新 0 行(I3) |
| 500 | 50000 | 系统内部错误 | 兜底,不泄漏堆栈 |

---

## 8. 关键流程设计

### 8.0 设计动机与演进

- **v1.0 问题**:`SELECT ... FOR UPDATE` 将行锁持有时间拉长为整个事务,热点书请求在 DB 串行排队,"无库存"这类必败请求也要先拿锁才失败。
- **v1.1 方案**:Redis Lua 预扣减 + DB 无锁落库,DB 压力大幅下降。
- **v1.2 → v1.5 撤销**:v1.2 曾增加幂等键三层架构(Redis 结果缓存 + DB 唯一列 + PENDING 状态机)。评审认为超出作业范围("幂等有点多余")—— 借/还本身是**自然幂等安全**的操作:重复借阅被已借集合/唯一索引拦截,重复归还被条件更新拦截,重试只会得到业务错误、不会产生副作用。故 v1.5 移除幂等键,幂等键作为演进方向(§11 T13)。

### 8.1 借阅流程:Redis 预扣 + DB 无锁落库 + 失败补偿

```
POST /books/{id}/borrow
┌─ 第一步:Redis Lua 原子预扣(并发主战场,不碰 DB)─────────────────────┐
│  EVALSHA borrow.lua 1 次往返完成:                                  │
│    a. stock key 不存在 → 懒加载(回源 DB 初始化,§9.3)后重试一次      │
│    b. stock <= 0            → NO_STOCK   → 40901                   │
│    c. SISMEMBER 已借集合命中 → ALREADY   → 40902(含重试场景)       │
│    d. DECR stock + SADD 已借集合 → OK                              │
│  冲突请求在此被 O(1) 原子拦截,零 DB 压力                            │
└────────────────────────────────────────────────────────────────────┘
┌─ 第二步:DB 事务落库(无任何行锁,乐观路径)───────────────────────────┐
│  @Transactional(无 FOR UPDATE):                                   │
│    a. INSERT borrow_record(active_flag=1, due_at=now+borrow-days)  │
│       → DuplicateKeyException(uk_user_book_active)→ 40902 + 补偿   │
│    b. UPDATE book SET available_copies = available_copies - 1      │
│       WHERE id=? AND available_copies > 0                          │
│       → 影响行数 = 0 → 40901 + 补偿                                │
│  锁持有时间 = 两条单语句的执行瞬间,而非整个业务流程                  │
└────────────────────────────────────────────────────────────────────┘
┌─ 第三步:失败补偿(DB 拒绝 → 回补 Redis)─────────────────────────────┐
│  第二步任一失败 → 执行 undo_borrow.lua 反向操作:                    │
│    INCR stock + SREM 已借集合(带并发交错守卫,§9.2)                 │
│  补偿失败 → 打 ERROR 日志 + 告警,由 DB 兜底约束保证不变量不破,      │
│  计数漂移由对账修复(§9.4,生产扩展点)                               │
└────────────────────────────────────────────────────────────────────┘
```

并发正确性分析:

- **不同用户抢同一本书(热点)**:在 Redis Lua 内原子串行(单线程模型),库存不会超发;**DB 侧不再出现锁排队**
- **同一用户并发重复借阅**:Redis `SISMEMBER` 原子拦截;极端情况下(Redis 数据丢失、降级路径)由 `uk_user_book_active` 唯一索引兜底
- **客户端重试**:与重复借阅同路径,收到 40902,**不产生任何副作用**
- **崩溃窗口**(Lua 预扣成功、DB 落库前进程死亡):库存计数漂移。此为双写无 MQ 方案的固有窗口,**通过 DB 兜底保证不超借,通过对账(§9.4)修复漂移**,作业范围内如实声明

### 8.2 归还流程(对称设计)

```
POST /books/{id}/return
1. Redis Lua 原子归还:
   a. SISMEMBER 已借集合 = 0 → NOT_BORROWED → 40903(含重试场景)
   b. SREM 已借集合 + INCR stock → OK
2. DB 事务落库(无行锁):
   a. UPDATE borrow_record SET status='RETURNED', returned_at=NOW(), active_flag=NULL
      WHERE user_id=? AND book_id=? AND status='BORROWED'
      → 影响行数 = 0 → 40903(执行补偿:SADD + DECR)
   b. UPDATE book SET available_copies = available_copies + 1 WHERE id=?
      (加法无超发风险,仅此一条即可)
3. 失败补偿与借阅对称(undo_return.lua,带并发交错守卫)
```

### 8.3 降级路径(Redis 不可用时的正确性保障)

```
Redis 连接失败 / 超时(脚本调用异常直接捕获,无需健康预判):
  → 直接走纯 DB 乐观路径(跳过第一步):
     a. INSERT borrow_record        -- uk_user_book_active 兜底 I2
     b. UPDATE ... WHERE available_copies > 0   -- 条件更新兜底 I1
     冲突 → 对应 409xx;正确性完全不受影响,仅性能回落
  → Redis 恢复后,库存 key 懒加载自动对齐(§9.3)
```

**要点**:Redis 是"性能加速器",不是"正确性依赖"。降级路径保证可用性与正确性在 Redis 完全不可用时依然成立。

### 8.4 读流程(Cache-Aside)

```
GET /books/{id}                     GET /borrowings(实现校准 v1.3)
   1. GET lib:book:{id}                1. 直查 MySQL(user_id,status 索引,毫秒级)
   2. 命中 → 直接返回                   2. Redis 已借集合仅服务于借/还并发控制,
   3. 未命中 → 查 MySQL                   不作为读数据源 —— 补偿失败/降级窗口期
   4. 写缓存 TTL=30min                    集合与 DB 短暂不一致时不误读
      (不存在也写空值标记,TTL=60s)
```

> v1.3 校准:F5 读路径由"Redis 集合优先"改为 DB 直查。该接口为每用户低频、索引查询,Redis 无性能增益,反而引入"集合与 DB 不一致时读到错误结果"的正确性风险。Redis 已借集合的职责收敛为:借/还的并发原子判定。

---

## 9. Redis 数据结构与一致性设计

### 9.1 Key 设计

| Key | 类型 | 内容 | TTL | 维护方 |
|-----|------|------|-----|--------|
| `lib:book:{id}:stock` | string(int) | 当前可借副本计数 | **无 TTL** | Lua 借/还原子维护;懒加载初始化 |
| `lib:user:{uid}:borrowed` | set | 该用户当前借阅中的 bookId 集合 | **无 TTL** | Lua 借(SADD)/还(SREM)原子维护;懒加载重建 |
| `lib:book:{id}` | string(JSON) | 书籍详情缓存 | 30 min ± 抖动 | Cache-Aside,借/还后主动删除 |
| `lib:books:page:{查询参数哈希}` | string(JSON) | 浏览分页结果 | 5 min | 仅 TTL(列表不含可借数,见 A10) |

**为什么并发相关 key 不设 TTL**:库存计数与已借集合是并发控制数据,过期即失效。可靠性交给"懒加载自愈 + DB 兜底",而非 TTL。

### 9.2 Lua 脚本(原子性关键)

```lua
-- borrow.lua:借阅预扣
-- KEYS[1]=lib:book:{id}:stock  KEYS[2]=lib:user:{uid}:borrowed  ARGV[1]=bookId
-- 返回: 0=OK  -1=NOT_INIT(库存未初始化)  1=NO_STOCK  2=ALREADY
local stock = redis.call('GET', KEYS[1])
if not stock then return -1 end
if tonumber(stock) <= 0 then return 1 end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then return 2 end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0
```

```lua
-- return.lua:归还回补(正常归还与失败补偿复用同一脚本)
-- 返回: 0=OK  -1=NOT_INIT  3=NOT_BORROWED
local stock = redis.call('GET', KEYS[1])
if not stock then return -1 end
local m = redis.call('SISMEMBER', KEYS[2], ARGV[1])
if m == 0 then return 3 end
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('INCR', KEYS[1])
return 0
```

```lua
-- undo_borrow.lua:借阅补偿(DB 落库失败后回补)
-- 并发交错守卫:仅当本次预扣的登记仍存在时回补 —— 若已被并发归还,对方已 INCR 抵销,跳过
-- undo_return.lua:归还补偿
-- 并发交错守卫:仅当该书不在已借集合时回补 —— 若已被并发再借,对方已 DECR 抵销,跳过
```

选 Lua 而非 Redisson 分布式锁的理由:预扣场景本身需要"读-判-写"原子,单个 Lua 脚本天然原子且**无锁等待、无锁超时/续期问题**,一次网络往返完成;分布式锁是通用互斥原语,用在这里反而引入锁粒度、超时、误删等额外复杂度(§11 T12)。

### 9.3 懒加载自愈(处理 Redis 重启 / 数据丢失)

```
stock key 缺失(NOT_INIT 或 Redis 重启后):
  1. SETNX lib:book:{id}:stock:loading 1 EX 5    -- 防并发重复加载
  2. 拿到锁者:SELECT available_copies FROM book WHERE id=? → SET stock
  3. 释放 loading 锁,重试业务 Lua(最多 1 次,仍失败 → 走降级路径)

已借集合缺失:
  → SELECT book_id FROM borrow_record WHERE user_id=? AND status='BORROWED'
  → SADD 重建集合,再响应查询
```

无论 Redis 丢失什么,都能从 DB 事实源重建 —— **自愈能力是本方案可靠性的支柱**。

### 9.4 一致性模型(如实声明)

| 场景 | 处理 | 一致性结果 |
|------|------|-----------|
| 正常借/还 | Lua 预扣/回补 + DB 落库同请求完成 | 提交后 Redis 与 DB 一致 |
| 重复提交/重试 | 被已借集合/唯一索引/条件更新拦截,返回 40902/40903 | 无副作用 |
| DB 拒绝(唯一索引/0 行) | 反向补偿 Redis | 一致(补偿成功) |
| 补偿失败 / 进程崩溃 | 告警;DB 约束兜底不变量;计数漂移 | 最终一致,漂移窗口 ≤ 对账周期 |
| Redis 故障 | 降级纯 DB 路径 | 强一致(仅性能回落) |
| Redis 重启 | 懒加载自愈重建 | 最终一致 |

> 生产扩展点(作业范围外,README 注明):定时对账任务 —— 按 `book` 表 `available_copies` 重放 stock key、按 `borrow_record` 重放用户已借集合。作业内以"补偿 + 懒加载 + DB 兜底"三层即可闭环,不实现对账。

---

## 10. 代码组织

按"分层 + 按职责分包",不过度引入 DDD 目录套件:

```
com.elibrary
├── ElibraryApplication.java
├── common/                        # 横切基础设施
│   ├── ApiResponse.java           # 统一响应体
│   ├── PageResponse.java
│   ├── ErrorCode.java             # 错误码枚举
│   ├── BizException.java          # 业务异常(code, message)
│   ├── GlobalExceptionHandler.java# @RestControllerAdvice
│   └── TraceIdFilter.java         # MDC traceId
├── config/                        # RedisTemplate、Lua Script Bean、MyBatis 分页插件、属性
├── domain/                        # 领域层(纯模型,零框架依赖)
│   ├── Book.java
│   ├── User.java
│   ├── BorrowRecord.java          # 含 isOverdue(now) 领域行为
│   └── enums/BookType.java, BorrowStatus.java
├── repository/                    # 持久层(仅无锁写入与查询)
│   ├── BookMapper.java            # 条件扣减/回补 UPDATE、查询
│   ├── UserMapper.java
│   └── BorrowRecordMapper.java    # INSERT、条件 UPDATE 归还、按用户查活跃
├── service/                       # 业务编排层
│   ├── BookService.java           # 浏览/详情(接缓存)
│   ├── BorrowService.java         # 编排:预扣 → 落库 → 补偿 | 降级分支
│   └── impl/
├── cache/                         # Redis 交互全部集中于此
│   ├── BorrowScriptService.java   # borrow/return/undo 脚本执行、结果映射
│   ├── StockLoader.java           # 懒加载 + loading 锁(§9.3)
│   ├── BorrowedSetLoader.java     # 已借集合懒重建
│   ├── BookCacheService.java      # 详情/分页缓存,失效时机集中
│   └── RedisHealth.java           # 健康探测(仅读缓存降级用;写路径靠异常捕获降级)
├── controller/                    # 表现层
│   ├── BookController.java
│   └── BorrowController.java
└── dto/                           # 出入参对象,与 Entity 严格分离
    ├── request/  borrow/return 请求
    └── response/ BookSummary, BookDetail, BorrowResult, BorrowingItem

src/main/resources/
├── lua/borrow.lua, return.lua, undo_borrow.lua, undo_return.lua
└── application.yml, schema.sql, data.sql
```

组织原则:

- **Entity ≠ DTO**:领域实体不直接暴露给 API,响应字段裁剪
- **Redis 交互单一出口**:所有 key 规则、Lua 执行、懒加载集中在 `cache/` 包,`service/` 只做编排
- **编排清晰可读**:`BorrowService.borrow()` 方法体即 §8.1 流程图的直译 —— 预扣、落库、补偿、降级四个步骤,每个步骤一个私有方法

---

## 11. 设计取舍(Trade-offs)—— 作业明确要求展示

| # | 决策 | 备选方案 | 选择理由 |
|---|------|----------|----------|
| T1 | **单体分层架构** | 微服务 | 4–6 小时作业,单体即可清晰展示分层与建模;微服务在此规模是过度设计 |
| T2 | MyBatis-Plus + 关键 SQL 手写 | 纯 MyBatis XML | 满足题目"用 MyBatis";Plus 免去 CRUD/分页样板,条件更新等关键 SQL 仍手写,不失控制力 |
| T3 | **Redis Lua 预扣减 + DB 无锁落库** | (v1.0)DB 行锁 `FOR UPDATE`;或库存完全放 Redis | FOR UPDATE 使热点书请求在 DB 串行排队,压力集中在锁等待;完全放 Redis 则丢失事实源、宕机即错账。本方案取"Redis 扛并发、DB 兜正确性"的平衡点,代价是补偿与懒加载的少量复杂度(A11 声明) |
| T4 | 动作式 API:`POST /books/{id}/borrow` | 资源式:`POST /borrowings` + `PATCH /borrowings/{id}/return` | 动作式语义直白、评审零歧义;资源式更"REST 纯正",作为演进方向写入 README |
| T5 | 手写 `CacheService` | Spring Cache `@Cacheable` | 注解省代码但 key/TTL/失效分散;并发场景下失效时机必须显式可控,集中管理对"可维护性"考察点更有利 |
| T6 | 单表 + `type` 判别电子书/期刊 | 每类内容独立表或继承映射 | 期刊特有字段(卷/期)当前无业务价值;单表简单直观 |
| T7 | Lombok | Java record / 手写 getter | 团队生态熟悉 Lombok;record 用于 DTO 也是好选择,二选一即可,不混用 |
| T8 | `X-User-Id` 头 + 预置用户 | 完整注册/登录/JWT | 认证是独立工程,与"借还书"核心域无关,声明为范围外(A1) |
| T9 | 逾期为派生状态,无定时任务 | 定时任务扫描逾期记录 | 借期 14 天,逾期仅展示时计算;无罚款需求则无需后台扫描 |
| T10 | 预扣失败**同步补偿**,不做 MQ 异步落库 | 预扣成功后异步落库 + MQ 补偿 | 异步方案吞吐更高,但引入 MQ 组件、消息延迟期内的状态模糊、对账复杂度成倍上升。本作业规模下同步落库足够(DB 无锁,写入即微秒级),异步化是明确的演进方向而非当前需要 |
| T11 | **Redis 降级路径必做**(§8.3) | 不实现降级 | 降级路径代码量很小(复用同一套 Mapper),换来 Redis 故障时的功能可用性;且它让"Redis 只优化性能、不影响正确性"的边界被代码固化,评审时是重要加分项 |
| T12 | Lua 原子脚本 | Redisson 分布式锁 | 预扣是"读-判-写"原子需求,Lua 单次往返天然满足;分布式锁带来锁超时、续期、误删、锁粒度设计等额外问题,且阻塞等待本身就是另一种排队 |
| T13 | **不提供幂等键**(v1.5 撤销 v1.2) | 幂等键三层架构(Redis 结果缓存 + DB 唯一列 + PENDING 状态机) | 借/还本身是自然幂等安全的:重复提交被已借集合/唯一索引/条件更新拦截,返回 40902/40903 且零副作用。幂等键的收益仅是"重放返回原成功结果"的体验优化,代价是 PENDING 状态机、幂等列、结果缓存的整套复杂度 —— 对 4–6 小时作业而言不成比例(评审意见:有点多余)。若未来客户端重试体验成为明确诉求,按演进方向补回 |

---

## 12. 错误处理策略

```
异常分层:
  BizException(code, message)        业务规则违例 → 4xx + 业务码
  ├── 参数校验失败(MethodArgumentNotValidException / HandlerMethodValidationException)→ 40001
  ├── 缺 X-User-Id → 40002
  ├── Redis 拒绝(预扣结果 NO_STOCK/ALREADY)→ 40901/40902
  ├── 重复键(DuplicateKeyException,uk_user_book_active)→ 40902(先补偿 Redis,再抛业务码)
  ├── Redis 连接故障 → 不抛错,切换降级路径(§8.3),打 WARN
  ├── 补偿失败 → 打 ERROR + traceId + 告警,业务仍按原结果返回
  └── 未知异常(Exception)→ 50000,记录完整堆栈,响应只含 code/message/traceId

实现:
  @RestControllerAdvice GlobalExceptionHandler
    - 每个已知异常类型一个 @ExceptionHandler
    - BizException 只打 WARN 日志(预期分支)
    - 未知异常打 ERROR + traceId,绝不回传堆栈(防信息泄漏)
```

---

## 13. 测试策略

| 层 | 工具 | 覆盖点 |
|----|------|--------|
| 领域 | JUnit 5 | `BorrowRecord.isOverdue` 边界(恰到期/已逾期/已归还) |
| Service 单测 | Mockito | 借/还全部分支:成功、无副本 40901、重复借 40902、无可还 40903、书不存在 40401、**DB 失败触发补偿** |
| **并发测试** | JUnit + 线程池 + **真实 Redis**(内嵌 redis-server 进程) | **20 线程并发借"仅 1 副本"的书 → 恰好 1 成功 19 个 40901**(守护 I1);同用户并发借同一书 → 恰好 1 成功(守护 I2);并发借还混合 → 计数始终 ∈ [0, totalCopies] |
| 重试安全测试 | 集成 | 重复借阅/重复归还 → 40902/40903,库存与记录零变化 |
| 补偿测试 | 集成 | 模拟 DB 写入失败(注入/唯一索引冲突)→ 断言 Redis stock 与已借集合**已回补原状** |
| 降级测试 | 集成 | mock Redis 不健康 → 借/还仍正确(唯一索引 + 条件更新兜底),返回正确错误码 |
| 懒加载测试 | 集成 | 删除 stock key → 下次借阅自动从 DB 重建,且并发下仅一次加载(loading 锁) |
| 集成测试 | MockMvc + MySQL | 全链路 API:状态码、响应体结构、错误码、X-User-Id 校验 |

> 并发测试是本作业正确性的核心证据,**必须打真实 Redis 链路**。实现校准(v1.3/v1.4):测试使用**内嵌真实 redis-server 进程**(codemonstur embedded-redis,测试 JVM 内启动真实 Redis),而非 mock —— 既有真实 Redis 的 Lua/单线程模型;数据库使用 **MySQL 独立测试库 e_library_test**(连接信息可用环境变量覆盖)。

---

## 14. 实施步骤(约 5–7 小时)

| 阶段 | 内容 | 预估 |
|------|------|------|
| M1 | 脚手架(Spring Initializr)+ docker-compose(MySQL/Redis)+ 配置 | 0.5h |
| M2 | 领域模型 + DDL + data.sql 种子数据 | 0.5h |
| M3 | F1/F2 浏览与详情 + Redis 详情缓存 | 1h |
| M4 | **借阅/归还主链路:Lua 脚本 + 无锁落库 + 补偿** | 1.5h |
| M5 | **降级路径 + 懒加载自愈**(§8.3/§9.3) | 0.5h |
| M6 | F5 我的借阅 + 统一错误处理 + traceId | 0.5h |
| M7 | 测试(并发/补偿/降级/重试安全)+ Swagger 验证 + README | 1–1.5h |

> 时间紧时的裁剪顺序:M5 降级与懒加载可先简化为"Redis 异常直接 500 或直查 DB"→ M4 中的补偿逻辑。**但并发测试(M7)不可裁剪**。

---

## 15. README 大纲(交付物之一)

1. 项目简介与功能清单
2. 快速开始:环境要求 → `docker-compose up -d` → 种子数据 → `mvn spring-boot:run` → Swagger 地址
3. API 文档摘要(表)与 curl 示例(含重试语义说明)
4. 设计要点:架构图、**并发方案(Redis 预扣 → DB 兜底 → 补偿 → 降级)**、缓存策略
5. 一致性模型与已知限制(§9.4,含对账扩展点、崩溃窗口说明)
6. 关键假设列表(§2)
7. 后续演进方向(T4 资源式 API、T13 幂等键、MQ 异步落库、续借/预约、卷期建模)

---

## 附:文件清单

```
e-library/
├── DESIGN.md            # 本文档
├── README.md            # 交付物
├── docker-compose.yml   # MySQL 8 + Redis 7
├── pom.xml
└── src/
    ├── main/java/com/elibrary/...
    ├── main/resources/{application.yml, schema.sql, data.sql, lua/*.lua}
    └── test/java/com/elibrary/...
```
