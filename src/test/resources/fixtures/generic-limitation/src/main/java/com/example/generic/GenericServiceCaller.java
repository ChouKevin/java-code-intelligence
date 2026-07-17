package com.example.generic;

import org.springframework.stereotype.Service;

/**
 * Scenario 3: Simple generic field — outer type resolves, inner type lost
 *
 * The field {@code AbstractCrudService<Order> crudService} strips to "AbstractCrudService".
 * This is sufficient to find the class and recurse into it.
 * Demonstrates that single-level generics work fine for call graph traversal.
 */
@Service
public class GenericServiceCaller {

    private final AbstractCrudService<Order> crudService;

    public GenericServiceCaller(AbstractCrudService<Order> crudService) {
        this.crudService = crudService;
    }

    /** crudService → AbstractCrudService (generic stripped, class found) */
    public void callGenericService() {
        crudService.deleteById(1L);
    }
}
