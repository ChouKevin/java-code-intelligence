package com.example.persistence;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface OrderMongoRepository extends MongoRepository<OrderEntity, String> {
}
