package com.example.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {
    OrderEntity findByOrderNo(String orderNo);
}
