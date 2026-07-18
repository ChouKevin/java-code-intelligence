package com.example.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.example.persistence.OrderEntity;
import com.example.persistence.OrderMapper;
import org.springframework.stereotype.Service;

@Service
public class OrderMyBatisPlusService extends ServiceImpl<OrderMapper, OrderEntity> {
    public OrderEntity findOrder(String orderNo) {
        return baseMapper.findByOrderNo(orderNo);
    }
}
