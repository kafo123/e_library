-- 归还回补:仅当已借集合含该书时才回补,原子
-- KEYS[1] = lib:book:{id}:stock        库存计数
-- KEYS[2] = lib:user:{uid}:borrowed    用户已借集合
-- ARGV[1] = bookId
-- 返回(整数):0=OK  -1=NOT_INIT  3=NOT_BORROWED(无可还的借阅)
local stock = redis.call('GET', KEYS[1])
if not stock then
  return -1
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
  return 3
end
redis.call('SREM', KEYS[2], ARGV[1])
redis.call('INCR', KEYS[1])
return 0
