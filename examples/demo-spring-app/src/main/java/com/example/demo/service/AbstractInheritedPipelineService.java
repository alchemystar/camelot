package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipeline;
import com.example.demo.pipeline.DynamicPipelineBuilder;
import com.example.demo.pipeline.ValidateStage;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class AbstractInheritedPipelineService {

    @Autowired
    protected ValidateStage validateStage;

    protected DynamicPipelineBuilder appendBaseStages(DynamicPipelineBuilder builder) {
        return builder.add(validateStage);
    }

    protected abstract DynamicPipelineBuilder appendCustomStages(DynamicPipelineBuilder builder);

    protected DynamicPipeline buildPipeline() {
        DynamicPipelineBuilder builder = DynamicPipeline.builder();
        builder = appendBaseStages(builder);
        builder = appendCustomStages(builder);
        return builder.build();
    }
}
