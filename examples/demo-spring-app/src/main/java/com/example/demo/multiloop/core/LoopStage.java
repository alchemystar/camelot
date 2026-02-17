package com.example.demo.multiloop.core;

import com.example.demo.multiloop.model.MultiLoopCtx;

public interface LoopStage {
    String apply(String input, MultiLoopCtx ctx);
}
