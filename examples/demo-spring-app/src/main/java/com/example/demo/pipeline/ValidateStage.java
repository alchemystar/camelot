package com.example.demo.pipeline;

import com.example.demo.service.RiskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ValidateStage implements PipelineStage {

    @Autowired
    private RiskService riskService;

    @Override
    public String apply(String input) {
        riskService.check(input);
        return input;
    }
}
