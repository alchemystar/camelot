package com.example.demo.interfacepipe.service;

import org.springframework.stereotype.Service;

@Service
public class VipDecorator {

    public String decorate(String payload, int score) {
        return "vip{" + score + "}" + payload;
    }
}
