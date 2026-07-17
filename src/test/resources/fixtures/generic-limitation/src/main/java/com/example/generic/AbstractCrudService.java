package com.example.generic;

import org.springframework.stereotype.Service;

/**
 * Scenario 2: Generic inheritance chain
 *
 * Base class with a generic type parameter T.
 * {@code save(T entity)} is defined here but subclasses bind T to a concrete type.
 * The call graph cannot propagate T → concrete type, so inherited methods
 * that reference T show up as "method source not found" in the subclass.
 */
@Service
public abstract class AbstractCrudService<T> {

    public void save(T entity) {
        System.out.println("Saving: " + entity);
    }

    public void deleteById(Long id) {
        System.out.println("Deleting: " + id);
    }
}
