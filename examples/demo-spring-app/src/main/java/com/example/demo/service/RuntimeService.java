package com.example.demo.service;

import org.springframework.stereotype.Service;

@Service
public class RuntimeService {

    public String start(String id) {
        return "runtime:" + id;
    }
}
