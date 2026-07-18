package com.example.basic;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BasicController {
    private final BasicService basicService = new BasicServiceImpl();

    @GetMapping("/basic/{id}")
    public String getBasic(@PathVariable Long id) {
        return basicService.getName(id);
    }
}
