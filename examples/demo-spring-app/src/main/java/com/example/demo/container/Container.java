package com.example.demo.container;

import com.example.demo.service.RuntimeService;

public class Container {

    private final RuntimeService runtimeService;
    private final String id;

    public Container(RuntimeService runtimeService, String id) {
        this.runtimeService = runtimeService;
        this.id = id;
    }

    public String start() {
        return runtimeService.start(id);
    }
}
