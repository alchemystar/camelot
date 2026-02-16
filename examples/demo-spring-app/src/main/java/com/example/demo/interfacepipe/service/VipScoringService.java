package com.example.demo.interfacepipe.service;

import org.springframework.stereotype.Service;

@Service
public class VipScoringService {

    public int score(String request) {
        if (request == null) {
            return 0;
        }
        return request.length() * 10;
    }
}
