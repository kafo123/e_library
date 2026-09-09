-- 借阅补偿(DB 落库失败后调用):仅当本次预扣的登记仍存在时回补
-- 若该书已被并发归还(集合中已不存在),说明对方已 INCR 抵销,跳过避免重复回补
-- KEYS[1] = lib:book:{id}:stock
-- KEYS[2] = lib:user:{uid}:borrowed
-- ARGV[1] = bookId
-- 返回(整数):恒为 0
local stock = redis.call('GET', KEYS[1])
if not stock then
  return 0
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  redis.call('SREM', KEYS[2], ARGV[1])
  redis.call('INCR', KEYS[1])
end
return 0
