package com.example.repository;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM users WHERE id = #{userId}")
    String findById(Long userId);

    @Select("SELECT * FROM users WHERE username = #{username}")
    String findByUsername(String username);
}
