package com.example.demo.controller;

import com.example.demo.container.ContainerFactory;
import com.example.demo.service.AuditService;
import com.example.demo.service.RuntimeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chain")
public class ChainController {

    @Autowired
    private ContainerFactory containerFactory;

    @Autowired
    private AuditService auditService;

    @Autowired
    private RuntimeService runtimeService;

    @GetMapping("/{id}")
    public String run(@PathVariable("id") String id) {
        auditService.recordRequest(id);
        String result = containerFactory.a(id).build().start();
        runtimeService.start("shadow-" + id);
        auditService.recordResponse(id, result);
        return result;
    }
}
