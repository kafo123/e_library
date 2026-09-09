# E-Library Service 电子图书馆服务

后端技术作业:电子图书馆平台,支持浏览、借阅、归还及查看当前借阅的数字内容(电子书/期刊)。

技术栈:**Java 17 + Spring Boot 3.3 + MyBatis-Plus + MySQL 8 + Redis 7**

完整设计文档见 [DESIGN.md](DESIGN.md)(含需求分析、关键假设、设计取舍)。

---

## 功能清单

| 功能 | 接口 |
|------|------|
| 浏览书籍(分页/关键字/类型/分类/排序) | `GET /api/v1/books` |
| 查询书籍详细资料(含实时可借数) | `GET /api/v1/books/{id}` |
| 借阅书籍 | `POST /api/v1/books/{id}/borrow` |
| 归还书籍 | `POST /api/v1/books/{id}/return` |
| 查看当前已借阅书籍(含逾期标记) | `GET /api/v1/borrowings` |

---

## 快速开始

### 1. 环境要求

- JDK 17+
- Maven 3.8+
- Docker(用于启动 MySQL 与 Redis)

### 2. 启动依赖

```bash
docker compose up -d        # MySQL 8 + Redis 7
```

### 3. 启动应用

```bash
mvn spring-boot:run
```

启动时自动执行 `schema.sql`(建表)与 `data.sql`(种子数据:3 个用户、8 本书),幂等可重复启动。

- API 文档(Swagger UI):http://localhost:8080/swagger-ui.html

### 4. 运行测试

测试使用 **MySQL**(独立测试库 `e_library_test`,连接信息可用 `MYSQL_HOST/MYSQL_PORT/MYSQL_USER/MYSQL_PASSWORD` 环境变量覆盖)+ **内嵌真实 redis-server 进程**。运行前先启动 MySQL:

```bash
docker compose up -d        # 若尚未启动
mvn test
```

---

## API 示例

### 浏览书籍

```bash
curl 'http://localhost:8080/api/v1/books?page=1&size=20&keyword=Java&type=EBOOK&sortBy=createdAt&order=desc'
```

### 借阅

```bash
curl -X POST 'http://localhost:8080/api/v1/books/1/borrow' -H 'X-User-Id: 1'
```

重试/重复提交会收到业务错误 `40902`(已借未还)或 `40903`(无可还记录),**不会产生任何副作用**(重复借阅被唯一索引/已借集合拦截,重复归还被条件更新拦截)。

### 归还 / 我的借阅

```bash
curl -X POST 'http://localhost:8080/api/v1/books/1/return' -H 'X-User-Id: 1'
curl 'http://localhost:8080/api/v1/borrowings?status=BORROWED' -H 'X-User-Id: 1'
```

### 统一响应结构

```json
{ "code": 0, "message": "success", "data": { }, "traceId": "..." }
```

错误码:40001 参数校验 / 40002 缺 X-User-Id / 40401 书不存在 /
40901 无可用副本 / 40902 已借未还 / 40903 无可还记录 / 50000 内部错误。

---

## 设计要点

### 并发方案:Redis 扛并发,DB 兜正确性(三层防线)

```
第一层:Redis Lua 原子预扣 ─── 库存检查 + 已借检查 + 扣减 + 登记,一次往返,拦截 99% 冲突
第二层:DB 条件写入 ───────── UPDATE ... WHERE available_copies > 0,锁持有时间为单条语句
第三层:DB 唯一索引 ───────── uk_user_book_active,不变量最终防线
```

- 不使用 `SELECT ... FOR UPDATE`(避免事务级行锁导致热点书请求在 DB 串行排队)
- 失败补偿:DB 拒绝 → Lua 反向回补(带并发交错守卫)
- **降级路径**:Redis 故障自动切换纯 DB 路径,正确性不变,仅性能回落
- **懒加载自愈**:Redis 重启后库存/已借集合回源 DB 自动重建(loading 锁防并发重复加载)

### 缓存策略(Cache-Aside)

- 书籍详情:TTL 30min ± 抖动(防雪崩)+ 空值标记(防穿透),借/还后主动删除
- 浏览分页:TTL 5min(列表不含可借数,无需在借/还后失效)

---

## 一致性模型与已知限制

| 场景 | 一致性结果 |
|------|-----------|
| 正常借/还 | 提交后 Redis 与 DB 一致 |
| 重复提交/重试 | 无副作用,业务错误明确(40902/40903) |
| DB 拒绝 / 补偿失败 / 进程崩溃 | DB 约束保证不变量;计数漂移窗口 ≤ 对账周期(最终一致) |
| Redis 故障 | 强一致(纯 DB 路径) |
| Redis 重启 | 懒加载自愈(最终一致) |

已知限制(如实声明):

- Lua 预扣成功但 DB 落库前进程崩溃的极小窗口内,计数可能漂移,由 DB 约束保证不超借/不重复借,**生产环境建议增加定时对账任务**(按 `book.available_copies` 与 `borrow_record` 重放 Redis 计数,本作业范围外)
- 不做续借/预约/逾期罚款(题目范围外);逾期为读时派生状态
- 认证鉴权不在范围:以 `X-User-Id` 请求头标识用户(预置用户)

---

## 关键假设

1. 用户经 `X-User-Id` 标识,数据预置,无注册/登录
2. 借期 14 天(配置项 `library.borrow-days` 可调)
3. 馆藏副本模型:`total_copies` / `available_copies` 冗余计数
4. 同一用户对同一书同时至多一条未归还记录;历史保留,可再次借阅
5. 电子书与期刊同表 + `type` 判别字段;期刊卷期信息暂不建模
6. 浏览列表不展示实时可借数(详情页展示),避免列表缓存频繁失效
7. 分页默认 page=1/size=20,上限 size=100

---

## 代码结构

```
src/main/java/com/elibrary
├── common/      # ApiResponse/ErrorCode/BizException/GlobalExceptionHandler/TraceIdFilter
├── config/      # Redis(Lua 脚本 Bean)/MyBatis-Plus/AppProperties/Swagger
├── domain/      # Book/User/BorrowRecord(含 isOverdue 领域行为)+ 枚举
├── repository/  # BookMapper/UserMapper/BorrowRecordMapper(条件写入 SQL)
├── cache/       # BorrowScriptService/StockLoader/BorrowedSetLoader/BookCacheService/RedisHealth
├── service/     # BookService(浏览/详情)、BorrowService(借/还编排)
├── controller/  # BookController/BorrowController
└── dto/         # 出入参对象(与 Entity 严格分离)
src/main/resources
├── lua/         # borrow/return/undo_borrow/undo_return 原子脚本
└── schema.sql / data.sql
```

---

## 后续演进方向

- 接口幂等键(客户端重试语义要求更友好时:重放返回首次结果而非 409)
- 资源式 API(`POST /borrowings` + `PATCH /borrowings/{id}/return`)
- MQ 异步落库 + 对账任务(吞吐量要求提升时)
- 续借/预约/逾期提醒
- 期刊卷期建模、真正的用户认证(JWT)
