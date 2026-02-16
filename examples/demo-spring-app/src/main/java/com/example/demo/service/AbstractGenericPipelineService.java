package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipeline;

public abstract class AbstractGenericPipelineService<P extends DynamicPipeline> {

    protected abstract P createPipeline();

    protected abstract void assemble(P pipeline);

    public P build(String requestId) {
        P pipeline = createPipeline();
        assemble(pipeline);
        return pipeline;
    }
}
