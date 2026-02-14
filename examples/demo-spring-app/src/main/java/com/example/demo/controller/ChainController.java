package com.example.demo.controller;

import com.example.demo.container.ContainerFactory;
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

    @GetMapping("/{id}")
    public String run(@PathVariable("id") String id) {
        return containerFactory.a(id).build().start();
    }
}
