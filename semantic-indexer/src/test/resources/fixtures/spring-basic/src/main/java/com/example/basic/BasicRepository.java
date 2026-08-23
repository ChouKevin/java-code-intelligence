package com.example.basic;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface BasicRepository {
    String findById(Long id);
}
