package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipeline;
import com.example.demo.pipeline.DynamicPipelineBuilder;
import com.example.demo.pipeline.EnrichStage;
import com.example.demo.pipeline.LoadUserStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class InheritedPipelineService extends AbstractInheritedPipelineService {

    @Autowired
    private LoadUserStage loadUserStage;

    @Autowired
    private EnrichStage enrichStage;

    @Override
    protected void assemblyPipeline(DynamicPipeline dynamicPipeline) {
        dynamicPipeline.addHandler(loadUserStage);
        dynamicPipeline.addHandler(enrichStage);
    }

    public String run(String id) {
        return buildPipeline().execute(id);
    }
}
