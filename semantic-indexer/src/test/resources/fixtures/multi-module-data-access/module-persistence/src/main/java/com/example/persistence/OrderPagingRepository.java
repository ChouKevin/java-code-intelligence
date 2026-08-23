package com.example.persistence;

import org.springframework.data.repository.PagingAndSortingRepository;

public interface OrderPagingRepository extends PagingAndSortingRepository<OrderEntity, String> {
}
