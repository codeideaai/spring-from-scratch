import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** 只演示无代理、单线程的 Setter 循环，并验证创建失败后的缓存清理。 */
public class CycleLab {
  record Definition(Supplier<Object> constructor, BiConsumer<Object, Factory> populate) {}

  static class Factory {
    final Map<String, Definition> definitions = new HashMap<>();
    final Map<String, Object> ready = new HashMap<>();
    final Map<String, Object> early = new HashMap<>();
    final Set<String> creating = new HashSet<>();
    boolean failed;

    Object get(String name) {
      if (failed) throw new IllegalStateException("discard failed factory");
      if (ready.containsKey(name)) return ready.get(name);
      if (early.containsKey(name)) return early.get(name);
      Definition d = Objects.requireNonNull(definitions.get(name), "missing " + name);
      // 重复进入同一创建路径说明存在依赖环，本容器选择立即拒绝。
      if (!creating.add(name)) throw new IllegalStateException("constructor cycle: " + name);
      try {
        Object bean = d.constructor().get();
        // 先暴露已构造但未填充属性的对象，才能打断 Setter 相互依赖。
        early.put(name, bean);
        d.populate().accept(bean, this);
        // 属性填充成功后升级为完成对象，早期引用随后在 finally 中移除。
        ready.put(name, bean);
        return bean;
      } catch (RuntimeException | Error e) {
        // 其他已完成对象可能持有这个半成品，因此失败时整个工厂都必须废弃。
        failed = true;
        ready.clear();
        early.clear();
        throw e;
      } finally {
        early.remove(name);
        creating.remove(name);
      }
    }
  }

  static class A {
    B b;
  }

  static class B {
    A a;
  }

  public static void main(String[] args) {
    var f = new Factory();
    f.definitions.put("a", new Definition(A::new, (bean, ctx) -> ((A) bean).b = (B) ctx.get("b")));
    f.definitions.put("b", new Definition(B::new, (bean, ctx) -> ((B) bean).a = (A) ctx.get("a")));
    A a = (A) f.get("a");
    IocLab.check(a.b.a == a && f.early.isEmpty(), "early identity and cleanup");
    var broken = new Factory();
    broken.definitions.put(
        "a",
        new Definition(
            A::new,
            (bean, ctx) -> {
              ctx.get("b");
              throw new IllegalStateException("init");
            }));
    broken.definitions.put(
        "b", new Definition(B::new, (bean, ctx) -> ((B) bean).a = (A) ctx.get("a")));
    try {
      broken.get("a");
      throw new AssertionError("failure hidden");
    } catch (IllegalStateException expected) {
      IocLab.check(
          broken.ready.isEmpty() && broken.early.isEmpty(), "discard all polluted objects");
    }
    System.out.println("PASS cycle: setter identity, early cleanup, failed factory discarded");
  }
}
