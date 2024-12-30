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
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.手机号格式错误
            return Result.fail("手机号不合法");
        }
        //3.生成随机验证码
        String code = RandomUtil.randomNumbers(6);

        //4.将验证码存入redis
        stringRedisTemplate.opsForValue().setIfAbsent("login:code:" + phone, code, 2, TimeUnit.MINUTES);



        //5.发送验证码（此处不发送，在控制台打印出来即可）
        log.debug("发送验证码" + code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.手机号格式错误
            return Result.fail("手机号不合法");
        }


        //校验验证码
        String cacheCode = stringRedisTemplate.opsForValue().get("login:code:" + phone);
        String userCode = loginForm.getCode();
        if (!userCode.equals(cacheCode)) {
            return Result.fail("验证码不正确");
        }

        User user = query().eq("phone", phone).one();

        //4.用户不存在则注册新用户
        if (user == null) {
            user = createUserWithPhone(phone);
        }

        String token = UUID.randomUUID().toString(true);
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString()));
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);


        //设置有效期
        stringRedisTemplate.expire(tokenKey, 30, TimeUnit.MINUTES);

        
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //创建用户
        User user = new User();
        //设置手机号
        user.setPhone(phone);
        //设置昵称(默认名)，一个固定前缀+随机字符串
        user.setNickName("user_" + RandomUtil.randomString(8));
        //保存到数据库
        save(user);
        return user;
    }

    @Override
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    @Override
    public Result logout(HttpServletRequest request) {
        //删除redis对应的key和ThreadLocal中的用户数据
        String token = request.getHeader("authorization");

        if (token == null || token.isEmpty()) {
            return Result.fail("用户未登录");
        }
        String key = RedisConstants.LOGIN_USER_KEY + token;

        stringRedisTemplate.delete(key);
        UserHolder.removeUser();
        return Result.ok("登出成功");
    }
}
