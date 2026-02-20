package com.camelot.runtime.bootstrap;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

public final class MethodPathAdvice {

    private MethodPathAdvice() {
    }

    @Advice.OnMethodEnter
    public static void onEnter(@Advice.Origin Method method) {
        if (method == null) {
            return;
        }
        MethodPathRuntime.enter(
                method.getDeclaringClass().getName(),
                method.getName(),
                method.getParameterCount()
        );
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit() {
        MethodPathRuntime.exit();
    }
}
