package com.example.demo.complex.pattern;

public abstract class AbstractTemplateFlow<K, C extends Visitable<C>, R> {

    public R process(K key) {
        C context = createContext(key);
        FlowObserverBus<C, R> observerBus = createObserverBus(context);
        observerBus.notifyStart(context);

        GenericPipeline<C, R> pipeline = buildPipeline(context);
        R result = pipeline.execute(context);

        observerBus.notifyFinish(context, result);
        return finalizeResult(context, result);
    }

    protected abstract C createContext(K key);

    protected abstract GenericPipeline<C, R> buildPipeline(C context);

    protected abstract FlowObserverBus<C, R> createObserverBus(C context);

    protected R finalizeResult(C context, R result) {
        return result;
    }
}
