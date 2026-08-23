package com.example.basic;

import org.springframework.stereotype.Service;

@Service
public class BasicServiceImpl implements BasicService {
    private final BasicRepository basicRepository = null;

    @Override
    public String getName(Long id) {
        return basicRepository.findById(id);
    }
}
