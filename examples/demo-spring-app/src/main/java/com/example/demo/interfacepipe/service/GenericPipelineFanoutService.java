package com.example.demo.interfacepipe.service;

import com.example.demo.interfacepipe.core.Pipeline;
import com.example.demo.interfacepipe.model.FlowCtx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class GenericPipelineFanoutService {

    @Autowired
    @Qualifier("basicPipeline")
    private Pipeline<String, String, FlowCtx> basicPipeline;

    @Autowired
    @Qualifier("vipPipeline")
    private Pipeline<String, String, FlowCtx> vipPipeline;

    public String runBoth(String requestId) {
        FlowCtx ctx = new FlowCtx();
        String current = requestId;
        List<Pipeline<String, String, FlowCtx>> pipelines = Arrays.asList(basicPipeline, vipPipeline);
        for (Pipeline<String, String, FlowCtx> pipeline : pipelines) {
            current = pipeline.execute(current, ctx);
        }
        return current + "|trace=" + ctx.getTrace().size();
    }
}
