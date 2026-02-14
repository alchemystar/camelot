package com.example.demo.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PayAppService extends AbstractPayApp<PayAppService> {

    @Autowired
    private RuntimeService runtimeService;

    public String start() {
        return runtimeService.start("pay-app");
    }
}
