package io.github.codeideaai.library;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Predicate;

/** An invocation owns its cursor; the immutable advice list can be shared between requests. */
public final class Advisors {
  private Advisors() {}

  @FunctionalInterface
  public interface Around {
    Object invoke(Call call) throws Throwable;
  }

  public record Advice(Predicate<Method> matches, Around around) {}

  public static final class Call {
    private final Object target;
    private final Method method;
    private final Object[] arguments;
    private final List<Advice> chain;
    private int cursor;

    Call(Object target, Method method, Object[] arguments, List<Advice> chain) {
      this.target = target;
      this.method = method;
      this.arguments = arguments;
      this.chain = chain;
    }

    public Object proceed() throws Throwable {
      if (cursor < chain.size()) return chain.get(cursor++).around().invoke(this);
      try {
        return method.invoke(target, arguments);
      } catch (InvocationTargetException failure) {
        throw failure.getCause();
      }
    }
  }

  public static <T> T wrap(Class<T> contract, T target, List<Advice> advice) {
    List<Advice> immutable = List.copyOf(advice);
    Object proxy =
        Proxy.newProxyInstance(
            contract.getClassLoader(),
            new Class<?>[] {contract},
            (self, method, arguments) -> {
              if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                  case "equals" -> self == arguments[0];
                  case "hashCode" -> System.identityHashCode(self);
                  case "toString" -> "Proxy[" + contract.getSimpleName() + "]";
                  default -> throw new IllegalStateException("unknown Object method");
                };
              }
              Method implementation =
                  target.getClass().getMethod(method.getName(), method.getParameterTypes());
              List<Advice> chain =
                  immutable.stream().filter(a -> a.matches().test(implementation)).toList();
              return new Call(target, implementation, arguments, chain).proceed();
            });
    return contract.cast(proxy);
  }

  public static Advice transaction(Transactions transactions) {
    return new Advice(
        method -> method.getName().equals("reserve"),
        call ->
            transactions.run(
                () -> {
                  try {
                    return call.proceed();
                  } catch (Exception | Error failure) {
                    throw failure;
                  } catch (Throwable failure) {
                    throw new IllegalStateException(failure);
                  }
                }));
  }
}
