package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipeline;
import com.example.demo.pipeline.DynamicPipelineBuilder;
import com.example.demo.pipeline.PipelineStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PipelineAssemblerCoordinator {

    @Autowired
    private PipelineAssemblerBridge pipelineAssemblerBridge;

    public DynamicPipeline compose(PipelineStage first, PipelineStage second, PipelineStage third) {
        DynamicPipelineBuilder builder = DynamicPipeline.builder();
        builder = pipelineAssemblerBridge.attach(builder, first, second, third);
        return builder.build();
    }
}
