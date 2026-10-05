package io.github.codeideaai.library;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Constructor-only singleton container. Configure it on one thread before serving requests. */
public final class BeanBox implements AutoCloseable {
  private record Definition(Class<?> type, List<String> dependencies) {}

  private final Map<String, Definition> definitions = new LinkedHashMap<>();
  private final Map<String, Object> ready = new LinkedHashMap<>();
  private final Set<String> creating = new LinkedHashSet<>();
  private final List<AutoCloseable> owned = new ArrayList<>();
  private final List<BiFunction<String, Object, Object>> processors = new ArrayList<>();
  private boolean frozen;
  private boolean closed;

  private void editable(String name) {
    if (frozen || closed) throw new IllegalStateException("configuration closed");
    if (ready.containsKey(name) || definitions.containsKey(name))
      throw new IllegalArgumentException("duplicate bean: " + name);
  }

  public void instance(String name, Object borrowed) {
    editable(name);
    ready.put(name, java.util.Objects.requireNonNull(borrowed));
  }

  public void define(String name, Class<?> type, String... dependencies) {
    editable(name);
    definitions.put(name, new Definition(type, List.of(dependencies)));
  }

  public void process(BiFunction<String, Object, Object> processor) {
    if (frozen || closed) throw new IllegalStateException("configuration closed");
    processors.add(processor);
  }

  public Object get(String name) {
    if (closed) throw new IllegalStateException("container closed");
    frozen = true;
    if (ready.containsKey(name)) return ready.get(name);
    Definition definition = definitions.get(name);
    if (definition == null) throw new IllegalArgumentException("missing bean: " + name);
    if (!creating.add(name))
      throw new IllegalStateException("dependency cycle: " + creating + " -> " + name);
    try {
      Object[] arguments = definition.dependencies().stream().map(this::get).toArray();
      List<Constructor<?>> candidates =
          Arrays.stream(definition.type().getConstructors())
              .filter(c -> compatible(c.getParameterTypes(), arguments))
              .toList();
      if (candidates.size() != 1)
        throw new IllegalArgumentException("ambiguous constructor: " + name);
      Object raw = candidates.get(0).newInstance(arguments);
      // Track the resource before processors run: even a failed wrapper must release it.
      if (raw instanceof AutoCloseable closeable) owned.add(closeable);
      Object exposed = raw;
      for (var processor : processors)
        exposed = java.util.Objects.requireNonNull(processor.apply(name, exposed));
      ready.put(name, exposed);
      return exposed;
    } catch (ReflectiveOperationException failure) {
      Throwable cause =
          failure instanceof InvocationTargetException invocation ? invocation.getCause() : failure;
      throw new IllegalStateException("creation failed: " + name, cause);
    } finally {
      creating.remove(name);
    }
  }

  private static boolean compatible(Class<?>[] types, Object[] arguments) {
    if (types.length != arguments.length) return false;
    for (int i = 0; i < types.length; i++) if (!types[i].isInstance(arguments[i])) return false;
    return true;
  }

  public <T> T get(String name, Class<T> type) {
    return type.cast(get(name));
  }

  public void start() {
    try {
      for (String name : definitions.keySet()) get(name);
    } catch (RuntimeException | Error failure) {
      try {
        close();
      } catch (RuntimeException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  public void close() {
    if (closed) return;
    closed = true;
    RuntimeException failure = null;
    for (int i = owned.size() - 1; i >= 0; i--) {
      try {
        owned.get(i).close();
      } catch (Exception error) {
        if (failure == null) failure = new IllegalStateException("shutdown failed", error);
        else failure.addSuppressed(error);
      }
    }
    owned.clear();
    ready.clear();
    if (failure != null) throw failure;
  }
}
