package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipelineBuilder;
import com.example.demo.pipeline.PipelineStage;
import org.springframework.stereotype.Service;

@Service
public class PipelineAssemblerHelper {

    public DynamicPipelineBuilder push(DynamicPipelineBuilder builder, PipelineStage stage) {
        return builder.add(stage);
    }
}
