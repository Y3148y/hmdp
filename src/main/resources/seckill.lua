-- 参数列表
-- 1.1. 优惠券id
local voucherId = ARGV[1]
-- 1.2. 用户id
local userId = ARGV[2]
-- 1.3. 订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1. 库存key
local stockKey = "seckill:stock:" .. voucherId
-- 2.2. 订单key
local orderKey = "seckill:order:" .. voucherId

-- 3.脚本业务
-- 3.1. 判断库存是否充足（key 不存在视为库存不足）
local stock = redis.call('get', stockKey)
if(not stock or tonumber(stock) <= 0) then
    -- 3.2. 库存不足
    return 1
end
-- 3.2. 判断用户是否重复抢购
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3. 用户重复抢购
    return 2
end
-- 3.4. 扣减库存
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
-- 3.6. 发送消息到队列中， XADD stream.order * k1 v1
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
