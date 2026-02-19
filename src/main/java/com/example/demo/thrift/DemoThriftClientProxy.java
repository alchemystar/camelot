package com.example.demo.thrift;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;

public class DemoThriftClientProxy implements FactoryBean<Object>, InitializingBean {

    private String serviceInterface;

    public void setServiceInterface(String serviceInterface) {
        this.serviceInterface = serviceInterface;
    }

    @Override
    public void afterPropertiesSet() {
        throw new IllegalStateException("Real Thrift client should not initialize in runtime bootstrap");
    }

    @Override
    public Object getObject() throws Exception {
        Class<?> interfaceType = getObjectType();
        if (interfaceType == null) {
            return null;
        }
        ClassLoader loader = interfaceType.getClassLoader();
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                return null;
            }
        };
        return Proxy.newProxyInstance(loader, new Class[]{interfaceType}, handler);
    }

    @Override
    public Class<?> getObjectType() {
        if (serviceInterface == null || serviceInterface.trim().isEmpty()) {
            return null;
        }
        try {
            return Class.forName(serviceInterface.trim(), false, Thread.currentThread().getContextClassLoader());
        } catch (Throwable error) {
            return null;
        }
    }

    @Override
    public boolean isSingleton() {
        return true;
    }
}
