package com.camelot.analyzer.sandboxdemo;

import java.util.ArrayList;
import java.util.List;

public class DemoPipelineBuilder {
    private final List<DemoStage> stages = new ArrayList<DemoStage>();

    private DemoPipelineBuilder() {
    }

    public static DemoPipelineBuilder builder() {
        return new DemoPipelineBuilder();
    }

    public DemoPipelineBuilder add(DemoStage stage) {
        if (stage != null) {
            stages.add(stage);
        }
        return this;
    }

    public DemoPipeline build() {
        return new DemoPipeline(new ArrayList<DemoStage>(stages));
    }
}
