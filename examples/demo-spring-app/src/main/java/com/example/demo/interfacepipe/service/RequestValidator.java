package com.example.demo.interfacepipe.service;

import org.springframework.stereotype.Service;

@Service
public class RequestValidator {

    public String check(String request) {
        if (request == null) {
            return "";
        }
        return request.trim();
    }
}
