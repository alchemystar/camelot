package com.example.demo.multiloop.impl;

import com.example.demo.multiloop.core.LoopStage;
import com.example.demo.multiloop.model.MultiLoopCtx;
import com.example.demo.multiloop.service.MultiLoopTraceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("loopStage03")
public class LoopStage03 implements LoopStage {

    @Autowired
    private MultiLoopTraceService multiLoopTraceService;

    @Override
    public String apply(String input, MultiLoopCtx ctx) {
        String before = multiLoopTraceService.beforeStage("03", input);
        ctx.addTrace(before);
        String output = input + "|s03";
        String after = multiLoopTraceService.afterStage("03", output);
        ctx.addTrace(after);
        return output;
    }
}
