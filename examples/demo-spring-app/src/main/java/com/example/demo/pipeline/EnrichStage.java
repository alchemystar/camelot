package com.example.demo.pipeline;

import com.example.demo.service.UserEnricherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EnrichStage implements PipelineStage {

    @Autowired
    private UserEnricherService userEnricherService;

    @Override
    public String apply(String input) {
        return userEnricherService.decorate(input);
    }
}
