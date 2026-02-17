package com.example.demo.multiloop.service;

import com.example.demo.multiloop.core.LoopStage;
import com.example.demo.multiloop.model.MultiLoopCtx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class MultiLoopPipelineService {

    @Autowired
    @Qualifier("loopStage01")
    private LoopStage stage01;

    @Autowired
    @Qualifier("loopStage02")
    private LoopStage stage02;

    @Autowired
    @Qualifier("loopStage03")
    private LoopStage stage03;

    @Autowired
    @Qualifier("loopStage04")
    private LoopStage stage04;

    @Autowired
    @Qualifier("loopStage05")
    private LoopStage stage05;

    @Autowired
    @Qualifier("loopStage06")
    private LoopStage stage06;

    @Autowired
    @Qualifier("loopStage07")
    private LoopStage stage07;

    @Autowired
    @Qualifier("loopStage08")
    private LoopStage stage08;

    @Autowired
    @Qualifier("loopStage09")
    private LoopStage stage09;

    @Autowired
    @Qualifier("loopStage10")
    private LoopStage stage10;

    @Autowired
    private MultiLoopTraceService multiLoopTraceService;

    public String run(String input) {
        MultiLoopCtx ctx = new MultiLoopCtx();
        String current = multiLoopTraceService.beginLoop(input);
        LoopStage[] stages = new LoopStage[] {
                stage01, stage02, stage03, stage04, stage05,
                stage06, stage07, stage08, stage09, stage10
        };
        for (LoopStage stage : stages) {
            current = stage.apply(current, ctx);
        }
        return multiLoopTraceService.finishLoop(current, ctx.size());
    }
}
