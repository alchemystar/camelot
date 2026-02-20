package com.camelot.runtime.bootstrap;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class EntryCallChainExecutor {

    private EntryCallChainExecutor() {
    }

    static InvokeResult invokeAndWriteDot(Object context, String signature, String dotFilePath) {
        if (context == null) {
            throw new IllegalArgumentException("Spring context must not be null");
        }
        EntrySpec spec = EntrySpec.parse(signature);
        ClassLoader classLoader = context.getClass().getClassLoader();
        Class<?> entryType = loadClass(spec.className, classLoader);
        Object bean = resolveBean(context, entryType);
        Method method = resolveMethod(bean, spec.methodName, spec.argCount);
        Object[] args = buildArgs(method.getParameterTypes());

        invokeBridgeNoArg(context, "clearCallChain");
        invokeBridgeNoArg(context, "enableCallChain");
        Object returnValue;
        try {
            returnValue = invokeMethod(bean, method, args);
        } finally {
            invokeBridgeNoArg(context, "disableCallChain");
        }
        String dotText = invokeBridgeString(context, "snapshotCallChainDot");
        Path dotPath = writeDot(dotFilePath, dotText);
        return new InvokeResult(spec.asText(), method.getDeclaringClass().getName(), method.getName(), dotPath, returnValue);
    }

    private static Class<?> loadClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException error) {
            throw new IllegalArgumentException("Entry class not found: " + className, error);
        }
    }

    private static Object resolveBean(Object context, Class<?> entryType) {
        try {
            Method getBeanByType = context.getClass().getMethod("getBean", Class.class);
            return getBeanByType.invoke(context, entryType);
        } catch (Throwable ignored) {
            // fallback below
        }
        try {
            Method getBeansOfType = context.getClass().getMethod("getBeansOfType", Class.class);
            Object value = getBeansOfType.invoke(context, entryType);
            if (value instanceof Map) {
                Map<?, ?> beans = (Map<?, ?>) value;
                if (!beans.isEmpty()) {
                    return beans.values().iterator().next();
                }
            }
        } catch (Throwable ignored) {
            // fallback below
        }
        throw new IllegalStateException("No bean found for entry class: " + entryType.getName());
    }

    private static Method resolveMethod(Object bean, String methodName, Integer argCount) {
        LinkedHashSet<Method> candidates = new LinkedHashSet<Method>();
        for (Method method : bean.getClass().getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            if (argCount != null && method.getParameterCount() != argCount.intValue()) {
                continue;
            }
            candidates.add(method);
        }
        if (candidates.isEmpty()) {
            for (Method method : bean.getClass().getDeclaredMethods()) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (argCount != null && method.getParameterCount() != argCount.intValue()) {
                    continue;
                }
                candidates.add(method);
            }
        }
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("No matched entry method: " + bean.getClass().getName() + "#" + methodName
                    + (argCount == null ? "" : "/" + argCount));
        }
        if (candidates.size() > 1 && argCount == null) {
            throw new IllegalArgumentException("Multiple overloaded methods matched. Please specify /argCount in entry signature.");
        }
        return candidates.iterator().next();
    }

    private static Object[] buildArgs(Class<?>[] parameterTypes) {
        if (parameterTypes == null || parameterTypes.length == 0) {
            return new Object[0];
        }
        Object[] args = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            args[index] = fakeValue(parameterTypes[index], 0, new HashSet<Class<?>>());
        }
        return args;
    }

    private static Object fakeValue(Class<?> type, int depth, Set<Class<?>> seen) {
        if (type == null) {
            return null;
        }
        if (depth > 2) {
            return null;
        }
        if (type == String.class || CharSequence.class.isAssignableFrom(type)) {
            return "";
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.FALSE;
        }
        if (type == byte.class || type == Byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class || type == Short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class || type == Long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class || type == Float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == double.class || type == Double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == char.class || type == Character.class) {
            return Character.valueOf('\0');
        }
        if (type == BigDecimal.class) {
            return BigDecimal.ZERO;
        }
        if (type == BigInteger.class) {
            return BigInteger.ZERO;
        }
        if (Date.class.isAssignableFrom(type)) {
            return new Date(0L);
        }
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants == null || constants.length == 0 ? null : constants[0];
        }
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (Collection.class.isAssignableFrom(type)) {
            if (Set.class.isAssignableFrom(type)) {
                return new LinkedHashSet<Object>();
            }
            if (Deque.class.isAssignableFrom(type)) {
                return new LinkedList<Object>();
            }
            return new ArrayList<Object>();
        }
        if (Map.class.isAssignableFrom(type)) {
            return new LinkedHashMap<Object, Object>();
        }
        if (type.isInterface()) {
            InvocationHandler handler = new DefaultInvocationHandler();
            return Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type}, handler);
        }
        if (Modifier.isAbstract(type.getModifiers())) {
            return null;
        }
        if (!seen.add(type)) {
            return null;
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object instance = constructor.newInstance();
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object currentValue = field.get(instance);
                if (currentValue != null) {
                    continue;
                }
                Object fakeFieldValue = fakeValue(field.getType(), depth + 1, seen);
                if (fakeFieldValue != null || !field.getType().isPrimitive()) {
                    field.set(instance, fakeFieldValue);
                }
            }
            return instance;
        } catch (Throwable ignored) {
            return null;
        } finally {
            seen.remove(type);
        }
    }

    private static Object invokeMethod(Object bean, Method method, Object[] args) {
        try {
            method.setAccessible(true);
            return method.invoke(bean, args);
        } catch (InvocationTargetException invocationError) {
            Throwable target = invocationError.getTargetException();
            if (target instanceof RuntimeException) {
                throw (RuntimeException) target;
            }
            throw new IllegalStateException("Entry invocation failed: " + method, target);
        } catch (Exception error) {
            throw new IllegalStateException("Entry invocation failed: " + method, error);
        }
    }

    private static void invokeBridgeNoArg(Object context, String methodName) {
        invokeBridge(context, methodName, Void.TYPE);
    }

    private static String invokeBridgeString(Object context, String methodName) {
        Object value = invokeBridge(context, methodName, String.class);
        return value == null ? "digraph CallChain {\n}\n" : String.valueOf(value);
    }

    private static Object invokeBridge(Object context, String methodName, Class<?> expectedType) {
        try {
            ClassLoader classLoader = context.getClass().getClassLoader();
            Class<?> bridgeClass = Class.forName("com.camelot.runtime.bootstrap.RuntimeLaunchBridge", true, classLoader);
            Method method = bridgeClass.getMethod(methodName);
            Object value = method.invoke(null);
            if (expectedType == Void.TYPE) {
                return null;
            }
            if (value == null || expectedType.isInstance(value)) {
                return value;
            }
            return value;
        } catch (Throwable error) {
            if (expectedType == String.class) {
                return "digraph CallChain {\n}\n";
            }
            return null;
        }
    }

    private static Path writeDot(String dotFilePath, String dotText) {
        String target = dotFilePath == null || dotFilePath.trim().isEmpty()
                ? "call-chain.dot"
                : dotFilePath.trim();
        Path path = Paths.get(target).toAbsolutePath().normalize();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(path, (dotText == null ? "" : dotText).getBytes(StandardCharsets.UTF_8));
            return path;
        } catch (IOException error) {
            throw new IllegalStateException("Failed to write dot file: " + path, error);
        }
    }

    static final class InvokeResult {
        private final String signature;
        private final String declaringClass;
        private final String methodName;
        private final Path dotPath;
        private final Object returnValue;

        private InvokeResult(String signature,
                             String declaringClass,
                             String methodName,
                             Path dotPath,
                             Object returnValue) {
            this.signature = signature;
            this.declaringClass = declaringClass;
            this.methodName = methodName;
            this.dotPath = dotPath;
            this.returnValue = returnValue;
        }

        String getSignature() {
            return signature;
        }

        String getDeclaringClass() {
            return declaringClass;
        }

        String getMethodName() {
            return methodName;
        }

        Path getDotPath() {
            return dotPath;
        }

        Object getReturnValue() {
            return returnValue;
        }
    }

    private static final class EntrySpec {
        private final String className;
        private final String methodName;
        private final Integer argCount;

        private EntrySpec(String className, String methodName, Integer argCount) {
            this.className = className;
            this.methodName = methodName;
            this.argCount = argCount;
        }

        private String asText() {
            return className + "#" + methodName + (argCount == null ? "" : "/" + argCount.intValue());
        }

        private static EntrySpec parse(String signature) {
            if (signature == null || signature.trim().isEmpty()) {
                throw new IllegalArgumentException("Entry signature must not be empty. Example: com.xxx.Service#paySync/1");
            }
            String trimmed = signature.trim();
            int hash = trimmed.indexOf('#');
            if (hash <= 0 || hash == trimmed.length() - 1) {
                throw new IllegalArgumentException("Invalid entry signature: " + signature);
            }
            String className = trimmed.substring(0, hash).trim();
            String methodAndArgs = trimmed.substring(hash + 1).trim();
            Integer argCount = null;
            String methodName = methodAndArgs;
            int slash = methodAndArgs.lastIndexOf('/');
            if (slash > 0 && slash < methodAndArgs.length() - 1) {
                String maybeCount = methodAndArgs.substring(slash + 1).trim();
                if (maybeCount.matches("\\d+")) {
                    argCount = Integer.valueOf(Integer.parseInt(maybeCount));
                    methodName = methodAndArgs.substring(0, slash).trim();
                }
            }
            if (className.isEmpty() || methodName.isEmpty()) {
                throw new IllegalArgumentException("Invalid entry signature: " + signature);
            }
            return new EntrySpec(className, methodName, argCount);
        }
    }

    private static final class DefaultInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            Class<?> returnType = method.getReturnType();
            if (returnType == Void.TYPE) {
                return null;
            }
            return fakeValue(returnType, 0, new HashSet<Class<?>>());
        }
    }
}
