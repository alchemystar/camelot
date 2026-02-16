package com.example.demo.complex.observer;

import com.example.demo.complex.model.ComplexFlowContext;
import com.example.demo.complex.pattern.FlowObserver;
import org.springframework.stereotype.Service;

@Service
public class AuditObserver implements FlowObserver<ComplexFlowContext<String, String>, String> {

    @Override
    public void onStart(ComplexFlowContext<String, String> context) {
        context.addTrace("observer-audit-start");
    }

    @Override
    public void onFinish(ComplexFlowContext<String, String> context, String result) {
        context.addTrace("observer-audit-finish");
        context.putAttribute("auditResultLength", Integer.valueOf(result == null ? 0 : result.length()));
    }
}
