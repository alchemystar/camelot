package com.example.demo.pipeline;

import java.util.ArrayList;
import java.util.List;

public class DynamicPipelineBuilder {
    private final List<PipelineStage> stages = new ArrayList<PipelineStage>();

    private DynamicPipelineBuilder() {
    }

    public static DynamicPipelineBuilder builder() {
        return new DynamicPipelineBuilder();
    }

    public DynamicPipelineBuilder add(PipelineStage stage) {
        if (stage != null) {
            stages.add(stage);
        }
        return this;
    }

    public DynamicPipeline build() {
        return new DynamicPipeline(new ArrayList<PipelineStage>(stages));
    }
}
