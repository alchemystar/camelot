package com.example.demo.interfacepipe.service;

import org.springframework.stereotype.Service;

@Service
public class PipelineAuditService {

    public void before(String pipelineName, String payload) {
        if (payload != null && payload.length() > 100) {
            payload.substring(0, 100);
        }
    }

    public void after(String pipelineName, String payload) {
        if (pipelineName != null && payload != null) {
            pipelineName.length();
            payload.length();
        }
    }
}
