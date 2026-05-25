package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class LoginInterceptor implements HandlerInterceptor {

//    @Override
//    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        /*
//        //1.获取session
//        HttpSession session = request.getSession();
//        //2.获取session中的用户
//        Object user = session.getAttribute("user");
//        //3.判断用户是否存在
//        if (user == null) {
//            //4.不存在，拦截，返回401
//            response.setStatus(401);
//            return false;
//        }
//        //5.存在，保存用户信息，放行
//        UserHolder.saveUser((UserDTO) user);
//        return true;
//         */
//        // 1. 获取请求头中的token
//        String token = request.getHeader("authorization");
//        if(StrUtil.isBlank(token)){
//            response.setStatus(401);
//            return false;
//        }
//        // 2. 基于TOKEN获取Redis用户
//        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(RedisConstants.LOGIN_USER_KEY+ token);
//        // 3. 判断用户是否存在
//        if(userMap.isEmpty()){
//            // 4. 不存在，拦截，返回401
//            response.setStatus(401);
//            return false;
//        }
//        // 5. 存在，将查询到的用户转换为UserDTO
//        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
//        // 6. 保存用户信息，放行
//        UserHolder.saveUser(userDTO);
//        // 7.刷新token有效期
//        stringRedisTemplate.expire(RedisConstants.LOGIN_USER_KEY+ token, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
//        return true;
//    }
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        //1. 判断是否要进行拦截(ThreadLocal 中是否有用户)
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            return false;
        }
        return true;
    }
}
