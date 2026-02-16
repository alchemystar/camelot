package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipeline;
import com.example.demo.pipeline.DynamicPipelineBuilder;
import com.example.demo.pipeline.ValidateStage;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class AbstractInheritedPipelineService {

    protected abstract void assemblyPipeline(DynamicPipeline dynamicPipeline);

    public DynamicPipeline buildPipeline() {
        DynamicPipelineBuilder builder = DynamicPipeline.builder();
        DynamicPipeline dynamicPipeline =builder.build();
        assemblyPipeline(dynamicPipeline);
        return dynamicPipeline;
    }
}
