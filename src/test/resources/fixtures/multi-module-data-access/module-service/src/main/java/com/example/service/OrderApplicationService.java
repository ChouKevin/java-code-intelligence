package com.example.service;

import com.example.persistence.JdbcOrderRepository;
import com.example.persistence.OrderJpaRepository;
import com.example.persistence.OrderMapper;
import org.springframework.stereotype.Service;

@Service
public class OrderApplicationService {
    private final OrderMapper orderMapper = null;
    private final OrderJpaRepository orderJpaRepository = null;
    private final JdbcOrderRepository jdbcOrderRepository = null;

    public String loadOrder(String orderNo) {
        orderMapper.findByOrderNo(orderNo);
        orderJpaRepository.findByOrderNo(orderNo);
        return jdbcOrderRepository.findName(orderNo);
    }
}
