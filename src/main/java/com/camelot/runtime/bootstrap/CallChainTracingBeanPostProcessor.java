package com.camelot.runtime.bootstrap;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

final class CallChainTracingBeanPostProcessor implements BeanPostProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(CallChainTracingBeanPostProcessor.class);

    private final CallChainCollector collector;
    private final List<String> packagePrefixes;

    CallChainTracingBeanPostProcessor(CallChainCollector collector, List<String> packagePrefixes) {
        this.collector = collector;
        this.packagePrefixes = normalizePackages(packagePrefixes);
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean == null) {
            return null;
        }
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        if (targetClass == null) {
            targetClass = bean.getClass();
        }
        if (targetClass == null) {
            return bean;
        }
        String className = targetClass.getName();
        if (!isTracedClass(className)) {
            return bean;
        }
        if (isInfrastructureBean(bean)) {
            return bean;
        }

        try {
            ProxyFactory proxyFactory = new ProxyFactory(bean);
            proxyFactory.setProxyTargetClass(true);
            proxyFactory.addAdvice(new TraceMethodInterceptor(collector, targetClass));
            return proxyFactory.getProxy(bean.getClass().getClassLoader());
        } catch (Throwable error) {
            LOG.debug("Skip call-chain proxy bean={} type={}", beanName, className, error);
            return bean;
        }
    }

    private boolean isInfrastructureBean(Object bean) {
        return bean instanceof BeanPostProcessor
                || bean instanceof org.springframework.aop.Advisor
                || bean instanceof org.aopalliance.aop.Advice
                || bean instanceof org.springframework.beans.factory.FactoryBean;
    }

    private boolean isTracedClass(String className) {
        if (className == null || className.trim().isEmpty()) {
            return false;
        }
        String clean = className.trim();
        if (clean.startsWith("java.")
                || clean.startsWith("javax.")
                || clean.startsWith("jakarta.")
                || clean.startsWith("org.springframework.")
                || clean.startsWith("com.camelot.runtime.")) {
            return false;
        }
        if (packagePrefixes.isEmpty()) {
            return true;
        }
        for (String prefix : packagePrefixes) {
            if (clean.equals(prefix) || clean.startsWith(prefix + ".")) {
                return true;
            }
        }
        return false;
    }

    private static List<String> normalizePackages(List<String> prefixes) {
        if (prefixes == null || prefixes.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<String>();
        for (String prefix : prefixes) {
            if (prefix == null) {
                continue;
            }
            String clean = prefix.trim();
            if (!clean.isEmpty()) {
                normalized.add(clean);
            }
        }
        return normalized.isEmpty() ? Collections.<String>emptyList() : new ArrayList<String>(normalized);
    }

    private static final class TraceMethodInterceptor implements MethodInterceptor {
        private final CallChainCollector collector;
        private final Class<?> targetClass;

        private TraceMethodInterceptor(CallChainCollector collector, Class<?> targetClass) {
            this.collector = collector;
            this.targetClass = targetClass;
        }

        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            Method method = invocation.getMethod();
            if (method == null || method.getDeclaringClass() == Object.class) {
                return invocation.proceed();
            }
            String node = targetClass.getName() + "#" + method.getName() + "/" + method.getParameterCount();
            collector.enter(node);
            try {
                return invocation.proceed();
            } finally {
                collector.exit();
            }
        }
    }
}
