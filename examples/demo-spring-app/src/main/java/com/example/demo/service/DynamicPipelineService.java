package com.example.demo.service;

import com.example.demo.pipeline.DynamicPipeline;
import com.example.demo.pipeline.EnrichStage;
import com.example.demo.pipeline.LoadUserStage;
import com.example.demo.pipeline.ValidateStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DynamicPipelineService {

    @Autowired
    private ValidateStage validateStage;

    @Autowired
    private LoadUserStage loadUserStage;

    @Autowired
    private EnrichStage enrichStage;

    public String run(String id) {
        return DynamicPipeline.builder()
                .add(validateStage)
                .add(loadUserStage)
                .add(enrichStage)
                .build()
                .execute(id);
    }
}
