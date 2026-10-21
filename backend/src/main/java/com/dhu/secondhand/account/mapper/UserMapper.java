package com.dhu.secondhand.account.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dhu.secondhand.account.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}