package com.example.demo.service;

import org.springframework.stereotype.Service;

@Service
public class RiskService {

    public boolean check(String id) {
        return id != null && id.startsWith("9");
    }
}
