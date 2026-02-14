package com.example.demo.controller;

import com.example.demo.service.LegacyService;

public class LegacyController {

    private LegacyService legacyService;

    public void setLegacyService(LegacyService legacyService) {
        this.legacyService = legacyService;
    }

    public String handleRequest(String id) {
        return legacyService.lookup(id);
    }
}
