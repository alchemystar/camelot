package com.example.demo.pipeline;

import java.util.List;

public class DynamicPipeline {
    private final List<PipelineStage> stages;

    DynamicPipeline(List<PipelineStage> stages) {
        this.stages = stages;
    }

    public static DynamicPipelineBuilder builder() {
        return DynamicPipelineBuilder.builder();
    }

    public String execute(String input) {
        String current = input;
        for (PipelineStage stage : stages) {
            current = stage.apply(current);
        }
        return current;
    }
}
