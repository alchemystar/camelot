package com.example.demo.complex.pattern;

import java.util.ArrayList;
import java.util.List;

public class FlowObserverBus<C extends Visitable<C>, R> {
    private final List<FlowObserver<C, R>> observers = new ArrayList<FlowObserver<C, R>>();

    public FlowObserverBus<C, R> addObserver(FlowObserver<C, R> observer) {
        if (observer != null) {
            observers.add(observer);
        }
        return this;
    }

    public void notifyStart(C context) {
        for (FlowObserver<C, R> observer : observers) {
            observer.onStart(context);
        }
    }

    public void notifyFinish(C context, R result) {
        for (FlowObserver<C, R> observer : observers) {
            observer.onFinish(context, result);
        }
    }
}
