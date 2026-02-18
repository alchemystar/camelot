package com.example.demo.multiloop.service;

import com.example.demo.multiloop.core.LoopStage;
import com.example.demo.multiloop.impl.LoopStage01;
import com.example.demo.multiloop.model.MultiLoopCtx;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

@Service
public class CglibProxyPipelineService {

    @Autowired
    @Qualifier("loopStage01")
    private LoopStage01 loopStage01;

    public String runWithCglib(String input) {
        MultiLoopCtx ctx = new MultiLoopCtx();
        LoopStage proxiedStage = createCglibProxy(loopStage01);
        String output = proxiedStage.apply(input, ctx);
        return output + "|ctx=" + ctx.size();
    }

    private LoopStage createCglibProxy(LoopStage01 target) {
        Enhancer enhancer = new Enhancer();
        enhancer.setSuperclass(LoopStage01.class);
        enhancer.setCallback(new MethodInterceptor() {
            @Override
            public Object intercept(Object obj, Method method, Object[] args, MethodProxy proxy) throws Throwable {
                if (method.getDeclaringClass() == Object.class) {
                    return method.invoke(target, args);
                }
                return proxy.invokeSuper(obj, args);
            }
        });
        LoopStage01 proxyInstance = (LoopStage01) enhancer.create();
        copyMutableFields(target, proxyInstance);
        return proxyInstance;
    }

    private void copyMutableFields(Object source, Object target) {
        Class<?> current = source.getClass();
        while (current != null && current != Object.class) {
            Field[] fields = current.getDeclaredFields();
            if (fields != null) {
                for (Field field : fields) {
                    if (field == null) {
                        continue;
                    }
                    int modifiers = field.getModifiers();
                    if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        field.set(target, field.get(source));
                    } catch (Throwable ignored) {
                        // best effort
                    }
                }
            }
            current = current.getSuperclass();
        }
    }
}
