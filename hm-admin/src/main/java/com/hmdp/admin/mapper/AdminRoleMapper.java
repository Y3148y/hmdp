package com.hmdp.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.admin.entity.AdminRole;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AdminRoleMapper extends BaseMapper<AdminRole> {

    @Select("SELECT r.* FROM admin_role r "
            + "JOIN admin_user_role ur ON r.id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<AdminRole> selectByUserId(@Param("userId") Long userId);
}
