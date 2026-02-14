package com.example.demo.container;

import com.example.demo.service.RuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ContainerFactory {

    @Autowired
    private RuntimeService runtimeService;

    public ContainerBuilder a(String id) {
        return new ContainerBuilder(runtimeService, id);
    }
}
