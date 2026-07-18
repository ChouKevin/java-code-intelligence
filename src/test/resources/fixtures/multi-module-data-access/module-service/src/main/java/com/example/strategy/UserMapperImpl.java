package com.example.strategy;

import com.example.repository.UserMapper;

public class UserMapperImpl implements UserMapper {

    @Override
    public String findById(Long userId) {
        return "u" + userId;
    }

    @Override
    public String findByUsername(String username) {
        return username;
    }
}
