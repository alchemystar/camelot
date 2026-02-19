package com.camelot.runtime.bootstrap;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cglib.proxy.Callback;
import org.springframework.cglib.proxy.Enhancer;
import org.springframework.cglib.proxy.Factory;
import org.springframework.cglib.proxy.MethodInterceptor;
import org.springframework.cglib.proxy.MethodProxy;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class NoOpBeanMockFactoryBean implements FactoryBean<Object>, BeanClassLoaderAware, InitializingBean {

    private String targetTypeName;
    private ClassLoader beanClassLoader = Thread.currentThread().getContextClassLoader();
    private Object singletonMock;
    private Class<?> targetType;

    public void setTargetTypeName(String targetTypeName) {
        this.targetTypeName = targetTypeName;
    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        if (classLoader != null) {
            this.beanClassLoader = classLoader;
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (targetTypeName == null || targetTypeName.trim().isEmpty()) {
            throw new IllegalStateException("targetTypeName is required for NoOpBeanMockFactoryBean");
        }
        Class<?> resolvedType = ClassUtils.forName(targetTypeName, beanClassLoader);
        this.targetType = normalizeTargetType(resolvedType);
        this.singletonMock = buildMock(this.targetType);
    }

    @Override
    public Object getObject() {
        return singletonMock;
    }

    @Override
    public Class<?> getObjectType() {
        return targetType;
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    private Object buildMock(Class<?> type) {
        if (Proxy.isProxyClass(type)) {
            return buildJdkProxyClassMock(type);
        }
        if (isCglibProxyClass(type)) {
            return buildCglibProxyClassMock(type);
        }
        if (type.isInterface()) {
            InvocationHandler handler = new DefaultInvocationHandler(type);
            ClassLoader loader = type.getClassLoader() != null ? type.getClassLoader() : beanClassLoader;
            return Proxy.newProxyInstance(loader, new Class[]{type}, handler);
        }
        if (Modifier.isFinal(type.getModifiers())) {
            // Cannot CGLIB-subclass final classes. Create constructor-free instance as a safe startup stub.
            return new ObjenesisStd(true).newInstance(type);
        }

        MethodInterceptor interceptor = new DefaultMethodInterceptor(type);
        Enhancer enhancer = new Enhancer();
        ClassLoader enhancerLoader = type.getClassLoader() != null ? type.getClassLoader() : beanClassLoader;
        enhancer.setClassLoader(enhancerLoader);
        enhancer.setSuperclass(type);
        enhancer.setCallbackType(MethodInterceptor.class);
        Class<?> proxyType = enhancer.createClass();

        ObjenesisStd objenesis = new ObjenesisStd(true);
        Object instance = objenesis.newInstance(proxyType);
        ((Factory) instance).setCallbacks(new Callback[]{interceptor});
        return instance;
    }

    private Class<?> normalizeTargetType(Class<?> resolvedType) {
        if (resolvedType == null) {
            return null;
        }
        if (isCglibProxyClass(resolvedType)) {
            Class<?> superClass = resolvedType.getSuperclass();
            if (superClass != null && superClass != Object.class) {
                return superClass;
            }
        }
        return resolvedType;
    }

    private Object buildJdkProxyClassMock(Class<?> proxyType) {
        InvocationHandler handler = new DefaultInvocationHandler(proxyType);
        try {
            return proxyType.getConstructor(InvocationHandler.class).newInstance(handler);
        } catch (Throwable ignored) {
            Class<?>[] interfaces = proxyType.getInterfaces();
            ClassLoader loader = proxyType.getClassLoader();
            return Proxy.newProxyInstance(loader, interfaces, handler);
        }
    }

    private Object buildCglibProxyClassMock(Class<?> proxyType) {
        MethodInterceptor interceptor = new DefaultMethodInterceptor(proxyType);
        ObjenesisStd objenesis = new ObjenesisStd(true);
        Object instance = objenesis.newInstance(proxyType);
        if (instance instanceof Factory) {
            ((Factory) instance).setCallbacks(new Callback[]{interceptor});
        }
        return instance;
    }

    private boolean isCglibProxyClass(Class<?> type) {
        String name = type.getName();
        return name.contains("$$EnhancerBySpringCGLIB$$")
                || name.contains("$$EnhancerByCGLIB$$")
                || name.contains("$$SpringCGLIB$$");
    }

    private static final class DefaultInvocationHandler implements InvocationHandler {
        private final Class<?> type;

        private DefaultInvocationHandler(Class<?> type) {
            this.type = type;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (isToString(method)) {
                return "NoOpMock<" + type.getName() + ">";
            }
            if (isHashCode(method)) {
                return Integer.valueOf(System.identityHashCode(proxy));
            }
            if (isEquals(method)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static final class DefaultMethodInterceptor implements MethodInterceptor {
        private final Class<?> type;

        private DefaultMethodInterceptor(Class<?> type) {
            this.type = type;
        }

        @Override
        public Object intercept(Object object, Method method, Object[] args, MethodProxy methodProxy) {
            if (isToString(method)) {
                return "NoOpMock<" + type.getName() + ">";
            }
            if (isHashCode(method)) {
                return Integer.valueOf(System.identityHashCode(object));
            }
            if (isEquals(method)) {
                return Boolean.valueOf(object == args[0]);
            }
            return defaultValue(method.getReturnType());
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == Void.TYPE) {
            return null;
        }
        if (!returnType.isPrimitive()) {
            if (Optional.class.equals(returnType)) {
                return Optional.empty();
            }
            if (List.class.equals(returnType)) {
                return Collections.emptyList();
            }
            if (Set.class.equals(returnType)) {
                return Collections.emptySet();
            }
            if (Map.class.equals(returnType)) {
                return Collections.emptyMap();
            }
            if (Collection.class.equals(returnType)) {
                return Collections.emptyList();
            }
            if (BigDecimal.class.equals(returnType)) {
                return BigDecimal.ZERO;
            }
            if (BigInteger.class.equals(returnType)) {
                return BigInteger.ZERO;
            }
            if (returnType.isArray()) {
                return Array.newInstance(returnType.getComponentType(), 0);
            }
            return null;
        }
        if (Boolean.TYPE.equals(returnType)) {
            return Boolean.FALSE;
        }
        if (Character.TYPE.equals(returnType)) {
            return Character.valueOf('\0');
        }
        if (Byte.TYPE.equals(returnType)) {
            return Byte.valueOf((byte) 0);
        }
        if (Short.TYPE.equals(returnType)) {
            return Short.valueOf((short) 0);
        }
        if (Integer.TYPE.equals(returnType)) {
            return Integer.valueOf(0);
        }
        if (Long.TYPE.equals(returnType)) {
            return Long.valueOf(0L);
        }
        if (Float.TYPE.equals(returnType)) {
            return Float.valueOf(0.0F);
        }
        if (Double.TYPE.equals(returnType)) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    private static boolean isToString(Method method) {
        return "toString".equals(method.getName()) && method.getParameterTypes().length == 0;
    }

    private static boolean isHashCode(Method method) {
        return "hashCode".equals(method.getName()) && method.getParameterTypes().length == 0;
    }

    private static boolean isEquals(Method method) {
        return "equals".equals(method.getName())
                && method.getParameterTypes().length == 1
                && method.getParameterTypes()[0] == Object.class;
    }
}
