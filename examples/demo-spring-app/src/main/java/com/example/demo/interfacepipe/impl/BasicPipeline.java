package com.example.demo.interfacepipe.impl;

import com.example.demo.interfacepipe.core.Pipeline;
import com.example.demo.interfacepipe.model.FlowCtx;
import com.example.demo.interfacepipe.service.PipelineAuditService;
import com.example.demo.interfacepipe.service.RequestValidator;
import com.example.demo.interfacepipe.service.ResponseAssembler;
import com.example.demo.interfacepipe.service.TaggingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class BasicPipeline implements Pipeline<String, String, FlowCtx> {

    @Autowired
    private RequestValidator requestValidator;

    @Autowired
    private TaggingService taggingService;

    @Autowired
    private ResponseAssembler responseAssembler;

    @Autowired
    private PipelineAuditService pipelineAuditService;

    @Override
    public String execute(String request, FlowCtx ctx) {
        String checked = requestValidator.check(request);
        String tagged = taggingService.tag(checked, "basic");
        pipelineAuditService.before("basic", tagged);
        ctx.addTrace("basic-before-assemble");
        String assembled = responseAssembler.assemble(tagged, ctx);
        pipelineAuditService.after("basic", assembled);
        return assembled;
    }
}
