package com.example.persistence;

import org.springframework.data.r2dbc.repository.R2dbcRepository;

public interface OrderR2dbcRepository extends R2dbcRepository<OrderEntity, String> {
}
