package io.knotra;

/**
 * 工厂定义的配置归一化契约。
 *
 * <p>schema 在挂载与每次重配置时调用，把宿主持有的原始配置转换为组件使用的类型化配置；
 * Runtime 不强加任何配置文件格式。返回 null 视为失败，抛出异常会使挂载或重配置事务被拒绝。
 * 无配置组件直接使用 {@link NoConfig#INSTANCE}，无需提供 schema。
 */
@FunctionalInterface
public interface ConfigSchema<C> {
    C validate(Object raw) throws Exception;
}
