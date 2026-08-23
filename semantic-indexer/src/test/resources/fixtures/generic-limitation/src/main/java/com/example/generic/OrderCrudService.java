package com.example.generic;

import org.springframework.stereotype.Service;

/**
 * Concrete subclass binding T = Order.
 *
 * {@code save()} and {@code deleteById()} are inherited from AbstractCrudService.
 * The call graph can resolve {@code deleteById()} (non-generic param),
 * but cannot resolve the inherited {@code save(T)} because T is not propagated.
 */
@Service
public class OrderCrudService extends AbstractCrudService<Order> {

    /** Calls inherited methods — both generic and non-generic params */
    public void processOrder(Order order) {
        save(order);
        deleteById(order.getId());
    }
}
