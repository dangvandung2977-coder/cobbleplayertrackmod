package com.cobbleverse.cobblesyncbridge.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Predicate;

public final class ReflectionUtils {
    private ReflectionUtils() {}

    public static Optional<Class<?>> loadClass(String name) {
        try {
            return Optional.of(Class.forName(name));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Object> getStaticFieldValue(Class<?> clazz, String fieldName) {
        try {
            Field field = clazz.getDeclaredField(fieldName);
            field.setAccessible(true);
            return Optional.ofNullable(field.get(null));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Object> callNoArgs(Object target, String methodName) {
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            return Optional.ofNullable(method.invoke(target));
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    public static Optional<Object> callIfPresent(Object target, String... methodNames) {
        for (String methodName : methodNames) {
            Optional<Object> value = callNoArgs(target, methodName);
            if (value.isPresent()) return value;
        }
        return Optional.empty();
    }

    public static Optional<Object> findAndInvoke(Object target, Predicate<Method> predicate, Object... args) {
        for (Method method : allMethods(target.getClass())) {
            if (!predicate.test(method)) continue;
            if (method.getParameterCount() != args.length) continue;
            if (!parametersCompatible(method.getParameterTypes(), args)) continue;
            try {
                method.setAccessible(true);
                return Optional.ofNullable(method.invoke(target, args));
            } catch (Throwable ignored) {
            }
        }
        return Optional.empty();
    }

    public static List<Method> allMethods(Class<?> clazz) {
        List<Method> methods = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null) {
            methods.addAll(Arrays.asList(current.getDeclaredMethods()));
            current = current.getSuperclass();
        }
        return methods;
    }

    public static Optional<Object> readField(Object target, String... fieldNames) {
        for (String fieldName : fieldNames) {
            Class<?> current = target.getClass();
            while (current != null) {
                try {
                    Field field = current.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return Optional.ofNullable(field.get(target));
                } catch (Throwable ignored) {
                    current = current.getSuperclass();
                }
            }
        }
        return Optional.empty();
    }

    public static boolean parametersCompatible(Class<?>[] parameterTypes, Object[] args) {
        for (int i = 0; i < parameterTypes.length; i++) {
            Object arg = args[i];
            if (arg == null) continue;
            Class<?> paramType = wrap(parameterTypes[i]);
            if (!paramType.isAssignableFrom(arg.getClass())) {
                return false;
            }
        }
        return true;
    }

    public static Class<?> wrap(Class<?> clazz) {
        if (!clazz.isPrimitive()) return clazz;
        if (clazz == int.class) return Integer.class;
        if (clazz == boolean.class) return Boolean.class;
        if (clazz == long.class) return Long.class;
        if (clazz == double.class) return Double.class;
        if (clazz == float.class) return Float.class;
        if (clazz == byte.class) return Byte.class;
        if (clazz == short.class) return Short.class;
        if (clazz == char.class) return Character.class;
        return clazz;
    }

    public static List<?> toList(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return list;
        if (value instanceof Collection<?> collection) return new ArrayList<>(collection);
        if (value instanceof Iterable<?> iterable) {
            List<Object> out = new ArrayList<>();
            for (Object o : iterable) out.add(o);
            return out;
        }
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            List<Object> out = new ArrayList<>(len);
            for (int i = 0; i < len; i++) out.add(java.lang.reflect.Array.get(value, i));
            return out;
        }
        return List.of(value);
    }
}
