-- 借阅预扣:原子完成"库存检查 + 已借检查 + 扣减 + 登记"
-- KEYS[1] = lib:book:{id}:stock        库存计数
-- KEYS[2] = lib:user:{uid}:borrowed    用户已借集合
-- ARGV[1] = bookId
-- 返回(整数):0=OK  -1=NOT_INIT(库存未初始化)  1=NO_STOCK  2=ALREADY(已借未还)
local stock = redis.call('GET', KEYS[1])
if not stock then
  return -1
end
if tonumber(stock) <= 0 then
  return 1
end
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
  return 2
end
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
return 0
