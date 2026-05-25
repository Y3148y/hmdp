package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    // ⭐ 使用ThreadLocal存储当前线程的用户信息
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
