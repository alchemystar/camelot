package com.example.demo.service;

import com.example.demo.pipeline.EnrichStage;
import com.example.demo.pipeline.LoadUserStage;
import com.example.demo.pipeline.ValidateStage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CrossClassPipelineService {

    @Autowired
    private ValidateStage validateStage;

    @Autowired
    private LoadUserStage loadUserStage;

    @Autowired
    private EnrichStage enrichStage;

    @Autowired
    private PipelineAssemblerCoordinator pipelineAssemblerCoordinator;

    public String run(String id) {
        return pipelineAssemblerCoordinator
                .compose(validateStage, loadUserStage, enrichStage)
                .execute(id);
    }
}
