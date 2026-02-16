package com.example.demo.controller;

import com.example.demo.service.CrossClassPipelineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pipeline/cross")
public class CrossClassPipelineController {

    @Autowired
    private CrossClassPipelineService crossClassPipelineService;

    @GetMapping("/{id}")
    public String run(@PathVariable("id") String id) {
        return crossClassPipelineService.run(id);
    }
}
