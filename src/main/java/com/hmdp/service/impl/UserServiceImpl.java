package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
//    @Autowired
//    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1. 校验手机号格式
        if(RegexUtils.isPhoneInvalid(phone)){
            //2. 不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        //3. 符合，生成验证码
        String code = RandomUtil.randomNumbers(6);
//        //4. 保存验证码到session
//        session.setAttribute("code", code);
        //4. 保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //5. 发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 可以改用邮箱发送，把电话号码改成邮箱增加邮箱正则表达式和正则匹配方法，使用邮箱Jmail发送code
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1. 校验
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            // 2. 不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        String code = loginForm.getCode();
        /*
        Object cacheCode = session.getAttribute("code");
        if(cacheCode == null || !cacheCode.toString().equals(code)){
            // 3. 不一致，返回错误信息
            return Result.fail("验证码错误");
        }
        // 4. 一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        // 5. 判断用户是否存在
        if(user == null){
            // 6. 不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        // 7. 保存用户信息到session中
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        return Result.ok();
        */
        //3. 从redis获取验证码
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        //4. 判断验证码是否存在
        if(cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //5. 验证码一致，根据手机号查询用户
        User user = query().eq("phone", phone).one();
        //6. 判断用户是否存在
        if(user == null){
            //7. 不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }
        //8. 保存用户信息到redis中
        // 1. 随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 2. 将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // UserDTO，ID为Long，所以不能用BeanUtil.beanToMap(userDTO)
        // Map<String, Object> userMap = BeanUtil.beanToMap(userDTO);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create().
                setIgnoreNullValue(true).
                setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY+token, userMap);
        // 3. 存储
        stringRedisTemplate.expire(LOGIN_USER_KEY+token, LOGIN_USER_TTL, TimeUnit.MINUTES);

        // 9. 返回token
        return Result.ok(token);

    }

    @Override
    public Result queryUserById(Long userId) {
        //1. 查询用户
        User user = getById(userId);
        if(user == null){
            return Result.ok();
        }
        //2. 封装成UserDTO
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    @Override
    public Result sign() {
        //1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //3. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //4. 写入Redis setbit key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth-1, true);
        return Result.ok();
    }

    @Override
    public Result signCount() {
        //1. 获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        //2. 获取日期
        LocalDateTime now = LocalDateTime.now();
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        //3. 获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        //4.获取本月截至今天为止的所有签到记录 bitfield sign:1014:202606 get u4 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth))
                        .valueAt(0)
        );
        if(result == null || result.isEmpty()){
            return Result.ok(0);
        }
        //5. 遍历，与1进行位运算
        Long num = result.get(0);
        if(num == null || num == 0L){
            return Result.ok(0);
        }
        int count = 0;
        while (true){
            if((num & 1) == 0){
                break;
            }else {
                num >>>= 1;
                count++;
            }
        }
        return Result.ok(count);
    }

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);// 保存用户, mybatisplus
        return user;
    }
}
