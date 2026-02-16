package com.example.demo.complex.visitor;

import com.example.demo.complex.model.ComplexFlowContext;
import com.example.demo.complex.pattern.Visitor;
import org.springframework.stereotype.Service;

@Service
public class SummaryVisitor implements Visitor<ComplexFlowContext<String, String>, String> {

    @Override
    public String visit(ComplexFlowContext<String, String> target) {
        StringBuilder builder = new StringBuilder();
        builder.append("key=").append(target.getRequestKey());
        builder.append(";payload=").append(target.getPayload());
        builder.append(";trace=").append(target.getTrace());
        return builder.toString();
    }
}
