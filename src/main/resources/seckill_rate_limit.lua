-- seckill_rate_limit.lua: ZSET 滑动窗口限流
-- ARGV[1] key prefix  (rate_limit:seckill:)
-- ARGV[2] user id
-- ARGV[3] current time millis
-- ARGV[4] window size (seconds)
-- ARGV[5] max requests
-- 返回: 0 放行, 1 限流

local key = ARGV[1] .. ARGV[2]
local now = tonumber(ARGV[3])
local window = tonumber(ARGV[4])
local maxReq = tonumber(ARGV[5])

-- 清理窗口外的旧记录
redis.call('ZREMRANGEBYSCORE', key, 0, now - window * 1000)

-- 计数
local count = redis.call('ZCARD', key)
if count >= maxReq then
    return 1
end

-- 用自增计数器保证同毫秒 member 唯一
local seq = redis.call('INCR', key .. ':seq')
redis.call('EXPIRE', key .. ':seq', 2)

-- 写入当前请求
redis.call('ZADD', key, now, now .. ':' .. seq)

-- 自动过期，防止不活跃用户的 key 泄漏
redis.call('EXPIRE', key, window * 2)

return 0
