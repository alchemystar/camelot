package com.camelot.analyzer.sandboxdemo;

import java.util.List;

public class DemoPipeline {
    private final List<DemoStage> stages;

    DemoPipeline(List<DemoStage> stages) {
        this.stages = stages;
    }

    public static DemoPipelineBuilder builder() {
        return DemoPipelineBuilder.builder();
    }

    public String execute(String input) {
        String current = input;
        for (DemoStage stage : stages) {
            current = stage.apply(current);
        }
        return current;
    }
}
