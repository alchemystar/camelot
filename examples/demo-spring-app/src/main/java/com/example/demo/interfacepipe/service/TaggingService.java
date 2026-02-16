package com.example.demo.interfacepipe.service;

import org.springframework.stereotype.Service;

@Service
public class TaggingService {

    public String tag(String payload, String tag) {
        return "[" + tag + "]" + payload;
    }
}
