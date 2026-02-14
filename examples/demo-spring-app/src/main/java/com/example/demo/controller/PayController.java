package com.example.demo.controller;

import com.example.demo.service.PayAppService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pay")
public class PayController {

    @Autowired
    private PayAppService payAppService;

    @GetMapping("/{id}")
    public String pay(@PathVariable("id") String id) {
        return payAppService.build(id).start();
    }
}
