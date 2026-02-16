package com.example.demo.interfacepipe.service;

import com.example.demo.interfacepipe.core.Pipeline;
import com.example.demo.interfacepipe.model.FlowCtx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class InterfacePipelineGateway {

    @Autowired
    private Pipeline<String, String, FlowCtx> pipeline;

    public String run(String requestId) {
        FlowCtx ctx = new FlowCtx();
        ctx.addTrace("gateway-start");
        String result = pipeline.execute(requestId, ctx);
        ctx.addTrace("gateway-finish");
        return result;
    }
}
