package com.example.persistence;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditDao {
    String latest();
}
