package com.example.demo.fail;

import javax.annotation.PostConstruct;

import org.springframework.stereotype.Component;

@Component
public class ExplodingInitBean {

    @PostConstruct
    public void init() {
        throw new IllegalStateException("ExplodingInitBean init failed");
    }
}
