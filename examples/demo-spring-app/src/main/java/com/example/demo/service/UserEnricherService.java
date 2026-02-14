package com.example.demo.service;

import org.springframework.stereotype.Service;

@Service
public class UserEnricherService {

    public String decorate(String rawUser) {
        return "decorated:" + rawUser;
    }
}
