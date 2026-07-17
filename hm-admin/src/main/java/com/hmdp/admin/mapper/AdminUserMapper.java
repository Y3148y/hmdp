package com.hmdp.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hmdp.admin.entity.AdminUser;
import org.apache.ibatis.annotations.Many;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

public interface AdminUserMapper extends BaseMapper<AdminUser> {

    @Select("SELECT * FROM admin_user WHERE username = #{username}")
    @Results({
            @Result(property = "id", column = "id"),
            @Result(property = "roles", column = "id",
                    many = @Many(select = "com.hmdp.admin.mapper.AdminRoleMapper.selectByUserId")),
            @Result(property = "permissions", column = "id",
                    many = @Many(select = "com.hmdp.admin.mapper.AdminPermissionMapper.selectByUserId"))
    })
    AdminUser selectByUsernameWithRoles(@Param("username") String username);
}
