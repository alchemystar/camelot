package com.example.demo.complex.pattern;

public interface FlowObserver<C extends Visitable<C>, R> {
    void onStart(C context);

    void onFinish(C context, R result);
}
