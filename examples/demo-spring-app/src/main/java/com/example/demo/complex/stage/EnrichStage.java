package com.example.demo.complex.stage;

import com.example.demo.complex.model.ComplexFlowContext;
import com.example.demo.complex.pattern.FlowStage;
import org.springframework.stereotype.Service;

@Service
public class EnrichStage implements FlowStage<ComplexFlowContext<String, String>> {

    @Override
    public ComplexFlowContext<String, String> apply(ComplexFlowContext<String, String> context) {
        String payload = context.getPayload();
        String enriched = payload + "|enriched:" + context.getRequestKey();
        context.setPayload(enriched);
        context.putAttribute("enriched", Boolean.TRUE);
        context.addTrace("enrich");
        return context;
    }
}
