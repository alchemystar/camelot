package com.example.demo.multiloop.impl;

import com.example.demo.multiloop.core.LoopStage;
import com.example.demo.multiloop.model.MultiLoopCtx;
import com.example.demo.multiloop.service.MultiLoopTraceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service("loopStage05")
public class LoopStage05 implements LoopStage {

    @Autowired
    private MultiLoopTraceService multiLoopTraceService;

    @Override
    public String apply(String input, MultiLoopCtx ctx) {
        String before = multiLoopTraceService.beforeStage("05", input);
        ctx.addTrace(before);
        String output = input + "|s05";
        String after = multiLoopTraceService.afterStage("05", output);
        ctx.addTrace(after);
        return output;
    }
}
