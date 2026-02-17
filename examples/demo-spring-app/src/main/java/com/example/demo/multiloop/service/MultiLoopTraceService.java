package com.example.demo.multiloop.service;

import org.springframework.stereotype.Service;

@Service
public class MultiLoopTraceService {

    public String beginLoop(String input) {
        return "begin:" + input;
    }

    public String beforeStage(String stageCode, String input) {
        return "before-" + stageCode + ":" + input;
    }

    public String afterStage(String stageCode, String output) {
        return "after-" + stageCode + ":" + output;
    }

    public String finishLoop(String output, int stageCount) {
        return "finish:" + stageCount + ":" + output;
    }
}
