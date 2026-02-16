package com.example.demo.interfacepipe.impl;

import com.example.demo.interfacepipe.core.Pipeline;
import com.example.demo.interfacepipe.model.FlowCtx;
import com.example.demo.interfacepipe.service.PipelineAuditService;
import com.example.demo.interfacepipe.service.RequestValidator;
import com.example.demo.interfacepipe.service.ResponseAssembler;
import com.example.demo.interfacepipe.service.VipDecorator;
import com.example.demo.interfacepipe.service.VipScoringService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VipPipeline implements Pipeline<String, String, FlowCtx> {

    @Autowired
    private RequestValidator requestValidator;

    @Autowired
    private VipScoringService vipScoringService;

    @Autowired
    private VipDecorator vipDecorator;

    @Autowired
    private ResponseAssembler responseAssembler;

    @Autowired
    private PipelineAuditService pipelineAuditService;

    @Override
    public String execute(String request, FlowCtx ctx) {
        String checked = requestValidator.check(request);
        int score = vipScoringService.score(checked);
        String decorated = vipDecorator.decorate(checked, score);
        pipelineAuditService.before("vip", decorated);
        ctx.addTrace("vip-before-assemble");
        String assembled = responseAssembler.assemble(decorated, ctx);
        pipelineAuditService.after("vip", assembled);
        return assembled;
    }
}
