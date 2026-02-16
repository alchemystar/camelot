package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipelineBuilder;
import com.example.demo.pipeline.PipelineStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class PipelineAssemblerBridge {

    @Autowired
    private PipelineAssemblerHelper pipelineAssemblerHelper;

    public DynamicPipelineBuilder attach(DynamicPipelineBuilder builder,
                                         PipelineStage first,
                                         PipelineStage second,
                                         PipelineStage third) {
        DynamicPipelineBuilder current = builder;
        current = pipelineAssemblerHelper.push(current, first);
        current = pipelineAssemblerHelper.push(current, second);
        current = pipelineAssemblerHelper.push(current, third);
        return current;
    }
}
