package com.example.demo.controller;

import com.example.demo.multiloop.service.MultiLoopPipelineService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/multi-loop")
public class MultiLoopPipelineController {

    @Autowired
    private MultiLoopPipelineService multiLoopPipelineService;

    @GetMapping("/{id}")
    public String run(@PathVariable("id") String id) {
        return multiLoopPipelineService.run(id);
    }
}
