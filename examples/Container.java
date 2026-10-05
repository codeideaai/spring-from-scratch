import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** 单线程教学容器：管理创建、注入、初始化、代理包装与销毁，拒绝依赖环。 */
public final class Container implements AutoCloseable {
  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  public @interface Inject {
    String value() default "";
  }

  // 用名称描述依赖，真正创建对象时再向容器递归解析。
  public record Ref(String name) {}

  public static final class Definition {
    final Class<?> type;
    boolean singleton = true;
    boolean lazy;
    Class<?>[] argumentTypes = new Class<?>[0];
    Object[] arguments = new Object[0];
    final Map<String, Object> properties = new LinkedHashMap<>();
    String init;
    String destroy;

    public Definition(Class<?> type) {
      this.type = type;
    }

    public Definition constructor(Class<?>[] types, Object... values) {
      argumentTypes = types.clone();
      arguments = values.clone();
      return this;
    }

    public Definition property(String name, Object value) {
      properties.put(name, value);
      return this;
    }

    public Definition lifecycle(String init, String destroy) {
      this.init = init;
      this.destroy = destroy;
      return this;
    }

    public Definition prototype() {
      singleton = false;
      return this;
    }

    public Definition lazy() {
      lazy = true;
      return this;
    }
  }

  public interface Processor {
    default Object before(Object bean, String name) {
      return bean;
    }

    default Object after(Object bean, String name) {
      return bean;
    }
  }

  private final Map<String, Definition> definitions = new LinkedHashMap<>();
  private final Map<String, Object> singletons = new LinkedHashMap<>();
  private final Set<String> creating = new LinkedHashSet<>();
  private final List<Processor> processors = new ArrayList<>();
  private final List<Runnable> destruction = new ArrayList<>();
  private final List<Consumer<String>> listeners = new ArrayList<>();
  private final Container parent;
  private boolean frozen;
  private boolean closed;

  public Container() {
    this(null);
  }

  public Container(Container parent) {
    this.parent = parent;
  }

  public void register(String name, Definition definition) {
    if (frozen || closed) throw new IllegalStateException("registration closed");
    if (definitions.putIfAbsent(name, definition) != null)
      throw new IllegalArgumentException("duplicate bean: " + name);
  }

  public void addProcessor(Processor p) {
    if (frozen || closed)
      throw new IllegalStateException("register processors before creating beans");
    processors.add(p);
  }

  public void onEvent(Consumer<String> listener) {
    listeners.add(listener);
  }

  public void refresh() {
    if (frozen || closed) throw new IllegalStateException("refresh only once, before getBean");
    frozen = true;
    try {
      // 只预创建非懒加载单例；全部成功后才广播刷新完成事件。
      for (var entry : definitions.entrySet())
        if (entry.getValue().singleton && !entry.getValue().lazy) getBean(entry.getKey());
      for (var listener : listeners) listener.accept("refreshed");
    } catch (RuntimeException | Error e) {
      try {
        close();
      } catch (RuntimeException cleanup) {
        e.addSuppressed(cleanup);
      }
      throw e;
    }
  }

  public Object getBean(String name) {
    if (closed) throw new IllegalStateException("container closed");
    frozen = true;
    // 先返回已完成的单例；创建失败的对象不会进入这个缓存。
    if (singletons.containsKey(name)) return singletons.get(name);
    Definition d = definitions.get(name);
    if (d == null) {
      // 本地定义优先，只有本地找不到名称时才委托父容器。
      if (parent != null) return parent.getBean(name);
      throw new IllegalArgumentException("no bean: " + name);
    }
    // 重复进入同一创建路径说明存在依赖环，本容器选择立即拒绝。
    if (!creating.add(name))
      throw new IllegalStateException("dependency cycle: " + creating + " -> " + name);
    try {
      // 先解析构造器依赖，再按显式声明的参数类型选择构造器。
      Object[] values = new Object[d.arguments.length];
      for (int i = 0; i < values.length; i++) values[i] = resolve(d.arguments[i]);
      Object raw = d.type.getConstructor(d.argumentTypes).newInstance(values);
      // 构造完成后再做 Setter 注入；重载不明确时拒绝猜测。
      for (var property : d.properties.entrySet()) {
        var candidates =
            Arrays.stream(d.type.getMethods())
                .filter(
                    m ->
                        m.getName().equals("set" + capitalize(property.getKey()))
                            && m.getParameterCount() == 1)
                .toList();
        if (candidates.size() != 1)
          throw new IllegalArgumentException("setter missing or ambiguous: " + property.getKey());
        candidates.get(0).invoke(raw, resolve(property.getValue()));
      }
      // 逐层扫描父类字段，避免遗漏继承而来的注入点。
      for (Class<?> type = d.type; type != Object.class; type = type.getSuperclass()) {
        for (Field field : type.getDeclaredFields()) {
          Inject inject = field.getAnnotation(Inject.class);
          if (inject == null) continue;
          if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers()))
            throw new IllegalArgumentException("cannot inject static/final: " + field);
          // 有名称时按名称查找，否则要求类型候选唯一；不能随意取第一个。
          Object value =
              inject.value().isEmpty() ? getBean(field.getType()) : getBean(inject.value());
          if (!field.getType().isInstance(value))
            throw new IllegalArgumentException("incompatible dependency: " + field);
          field.setAccessible(true);
          field.set(raw, value);
        }
      }
      Object exposed = raw;
      // 初始化之前完成前置处理，之后的后置处理器可以把对象包装成代理。
      for (Processor p : processors) exposed = Objects.requireNonNull(p.before(exposed, name));
      if (d.init != null) exposed.getClass().getMethod(d.init).invoke(exposed);
      for (Processor p : processors) exposed = Objects.requireNonNull(p.after(exposed, name));
      if (d.singleton) {
        // 缓存最终暴露的实例；有代理时，后续依赖注入也必须拿到这个代理。
        singletons.put(name, exposed);
        // 销毁回调绑定原始实例，JDK 接口代理可能没有暴露销毁方法。
        if (d.destroy != null) destruction.add(() -> invokeDestroy(raw, d.destroy));
      }
      return exposed;
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(
          "cannot create bean: " + name, e instanceof InvocationTargetException ? e.getCause() : e);
      // 成功与失败都清理创建标记，避免下一次查询被误判为循环。
    } finally {
      creating.remove(name);
    }
  }

  public <T> T getBean(Class<T> type) {
    var names =
        definitions.entrySet().stream()
            .filter(e -> type.isAssignableFrom(e.getValue().type))
            .map(Map.Entry::getKey)
            .toList();
    if (names.isEmpty() && parent != null) return parent.getBean(type);
    // 零个候选或多个候选都属于配置错误，错误信息保留候选名称。
    if (names.size() != 1)
      throw new IllegalArgumentException("expected one " + type.getName() + ", found " + names);
    return type.cast(getBean(names.get(0)));
  }

  private Object resolve(Object value) {
    return value instanceof Ref ref ? getBean(ref.name()) : value;
  }

  private static String capitalize(String s) {
    return Character.toUpperCase(s.charAt(0)) + s.substring(1);
  }

  private static void invokeDestroy(Object bean, String name) {
    try {
      bean.getClass().getMethod(name).invoke(bean);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("destroy failed", e);
    }
  }

  public void close() {
    if (closed) return;
    closed = true;
    RuntimeException failure = null;
    // 逆序释放已登记的单例；某个回调失败仍继续清理其余对象。
    for (int i = destruction.size() - 1; i >= 0; i--) {
      try {
        destruction.get(i).run();
      } catch (RuntimeException e) {
        if (failure == null) failure = e;
        else failure.addSuppressed(e);
      }
    }
    singletons.clear();
    destruction.clear();
    if (failure != null) throw failure;
  }
}
