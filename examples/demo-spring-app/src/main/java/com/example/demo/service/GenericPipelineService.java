package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipeline;
import com.example.demo.pipeline.EnrichStage;
import com.example.demo.pipeline.LoadUserStage;
import com.example.demo.pipeline.ValidateStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GenericPipelineService extends AbstractGenericPipelineService<DynamicPipeline> {

    @Autowired
    private ValidateStage validateStage;

    @Autowired
    private LoadUserStage loadUserStage;

    @Autowired
    private EnrichStage enrichStage;

    @Override
    protected DynamicPipeline createPipeline() {
        return DynamicPipeline.builder().build();
    }

    @Override
    protected void assemble(DynamicPipeline pipeline) {
        pipeline.addHandler(validateStage);
        pipeline.addHandler(loadUserStage);
        pipeline.addHandler(enrichStage);
    }

    public String run(String id) {
        return build(id).execute(id);
    }
}
