-- 归还补偿(DB 落库失败后调用):仅当该书不在已借集合时回补
-- 若用户已并发再次借阅(集合中已存在),说明对方已 DECR 抵销,跳过避免重复回补
-- KEYS[1] = lib:book:{id}:stock
-- KEYS[2] = lib:user:{uid}:borrowed
-- ARGV[1] = bookId
-- 返回(整数):恒为 0
local stock = redis.call('GET', KEYS[1])
if not stock then
  return 0
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 0 then
  redis.call('SADD', KEYS[2], ARGV[1])
  redis.call('DECR', KEYS[1])
end
return 0
