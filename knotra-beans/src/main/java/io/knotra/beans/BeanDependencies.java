package io.knotra.beans;

/**
 * 激活上下文中已声明依赖项的类型安全解析句柄。
 *
 * <p>仅允许解析通过 {@code .with(...)} 显式声明的 {@link BeanDependency}，
 * 若尝试解析未声明的依赖项将抛出 {@link IllegalArgumentException}。</p>
 */
public interface BeanDependencies {

    /**
     * 解析已声明依赖项在当前激活上下文中的绑定值。
     *
     * @param dependency 通过 {@link Beans} 创建的依赖声明句柄
     * @param <T> 依赖值类型；可选依赖为 {@code Optional<提供方类型>}
     * @return 依赖的解析值
     * @throws IllegalArgumentException 若该依赖项未在 Bean 定义中显式声明
     */
    <T> T get(BeanDependency<T> dependency);
}
