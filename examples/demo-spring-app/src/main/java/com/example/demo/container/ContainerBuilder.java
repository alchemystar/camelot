package com.example.demo.container;

import com.example.demo.service.RuntimeService;

public class ContainerBuilder {

    private final RuntimeService runtimeService;
    private final String id;

    public ContainerBuilder(RuntimeService runtimeService, String id) {
        this.runtimeService = runtimeService;
        this.id = id;
    }

    public Container build() {
        return new Container(runtimeService, id);
    }
}
