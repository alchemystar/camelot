package com.example.demo.controller;

import com.example.demo.complex.service.ComplexPatternService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/complex/pattern")
public class ComplexPatternController {

    @Autowired
    private ComplexPatternService complexPatternService;

    @GetMapping("/{id}")
    public String run(@PathVariable("id") String id) {
        return complexPatternService.run(id);
    }

    @GetMapping("/chain/{id}")
    public String runByChain(@PathVariable("id") String id) {
        return complexPatternService.runByChain(id);
    }
}
