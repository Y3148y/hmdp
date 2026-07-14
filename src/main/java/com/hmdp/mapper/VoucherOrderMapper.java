package com.hmdp.mapper;

import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface VoucherOrderMapper extends BaseMapper<VoucherOrder> {

    /**
     * 按分片键 ID 查询，ShardingSphere 路由到单张分表
     */
    VoucherOrder selectById(@Param("id") Long id);

    /**
     * 按用户+优惠券去重计数（广播查询，汇总 8 张分表结果）
     */
    int countByUserIdAndVoucherId(@Param("userId") Long userId, @Param("voucherId") Long voucherId);

    /**
     * 按用户 ID 分页查询订单（广播查询）
     */
    List<VoucherOrder> selectByUserId(@Param("userId") Long userId,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);
}
