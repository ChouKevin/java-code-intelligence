package com.example.persistence;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OrderMapper {
    @Select("SELECT * FROM orders WHERE order_no = #{orderNo}")
    OrderEntity findByOrderNo(String orderNo);

    @Select("SELECT * FROM orders WHERE status = #{status}")
    OrderEntity annotationOnly(String status);

    OrderEntity xmlOnly(Long customerId);
}
