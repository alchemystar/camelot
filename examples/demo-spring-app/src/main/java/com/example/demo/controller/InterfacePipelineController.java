package com.example.demo.controller;

import com.example.demo.interfacepipe.service.InterfacePipelineGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pipeline/interface")
public class InterfacePipelineController {

    @Autowired
    private InterfacePipelineGateway interfacePipelineGateway;

    @GetMapping("/{id}")
    public String run(@PathVariable("id") String id) {
        return interfacePipelineGateway.run(id);
    }
}
