package com.example.demo.complex.stage;

import com.example.demo.complex.model.ComplexFlowContext;
import com.example.demo.complex.pattern.FlowStage;
import org.springframework.stereotype.Service;

@Service
public class RiskStage implements FlowStage<ComplexFlowContext<String, String>> {

    @Override
    public ComplexFlowContext<String, String> apply(ComplexFlowContext<String, String> context) {
        String payload = context.getPayload();
        boolean risky = payload != null && payload.contains("risk");
        context.putAttribute("riskFlag", Boolean.valueOf(risky));
        context.addTrace("risk");
        return context;
    }
}
