package com.camelot.analyzer.sandboxdemo;

public abstract class AbstractFlowService {
    protected UpperStage upperStage;

    protected DemoPipelineBuilder appendBase(DemoPipelineBuilder builder) {
        DemoPipelineBuilder current = builder;
        current = current.add(upperStage);
        return current;
    }

    protected abstract DemoPipelineBuilder appendCustom(DemoPipelineBuilder builder);

    protected DemoPipeline buildPipeline() {
        DemoPipelineBuilder builder = DemoPipeline.builder();
        builder = appendBase(builder);
        builder = appendCustom(builder);
        return builder.build();
    }
}
