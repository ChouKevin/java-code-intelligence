package com.example.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcOrderRepository {
    private final JdbcTemplate jdbcTemplate = null;

    public String findName(String orderNo) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM orders WHERE order_no = ?",
                String.class,
                orderNo);
    }
}
