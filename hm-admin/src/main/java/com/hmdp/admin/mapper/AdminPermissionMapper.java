package com.hmdp.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.admin.entity.AdminPermission;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AdminPermissionMapper extends BaseMapper<AdminPermission> {

    @Select("SELECT p.* FROM admin_permission p "
            + "JOIN admin_role_permission rp ON p.id = rp.permission_id "
            + "WHERE rp.role_id = #{roleId}")
    List<AdminPermission> selectByRoleId(@Param("roleId") Long roleId);

    @Select("SELECT DISTINCT p.* FROM admin_permission p "
            + "JOIN admin_role_permission rp ON p.id = rp.permission_id "
            + "JOIN admin_user_role ur ON rp.role_id = ur.role_id "
            + "WHERE ur.user_id = #{userId}")
    List<AdminPermission> selectByUserId(@Param("userId") Long userId);
}
