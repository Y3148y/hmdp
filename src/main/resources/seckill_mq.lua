-- seckill_mq.lua: 秒杀资格预检（不含 XADD，消息由 RabbitMQ 发送）
-- 参数列表
-- ARGV[1] 优惠券id
-- ARGV[2] 用户id
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 数据key
local stockKey = "seckill:stock:" .. voucherId
local orderKey = "seckill:order:" .. voucherId

-- 判断库存是否充足（key 不存在视为库存不足）
local stock = redis.call('get', stockKey)
if(not stock or tonumber(stock) <= 0) then
    return 1
end
-- 判断用户是否重复抢购
if(redis.call('sismember', orderKey, userId) == 1) then
    return 2
end
-- 扣减库存
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
