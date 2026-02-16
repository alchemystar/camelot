package com.example.demo.interfacepipe.service;

import com.example.demo.interfacepipe.model.FlowCtx;
import org.springframework.stereotype.Service;

@Service
public class ResponseAssembler {

    public String assemble(String payload, FlowCtx ctx) {
        ctx.addTrace("assemble");
        return payload + "|trace=" + ctx.getTrace().size();
    }
}
