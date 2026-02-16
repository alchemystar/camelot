package com.example.demo.complex.stage;

import com.example.demo.complex.model.ComplexFlowContext;
import com.example.demo.complex.pattern.FlowStage;
import org.springframework.stereotype.Service;

@Service
public class NormalizeStage implements FlowStage<ComplexFlowContext<String, String>> {

    @Override
    public ComplexFlowContext<String, String> apply(ComplexFlowContext<String, String> context) {
        String payload = context.getPayload();
        String normalized = payload == null ? "" : payload.trim().toLowerCase();
        context.setPayload(normalized);
        context.addTrace("normalize");
        return context;
    }
}
