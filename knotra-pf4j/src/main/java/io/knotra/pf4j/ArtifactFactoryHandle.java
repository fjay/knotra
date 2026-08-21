package io.knotra.pf4j;

import io.knotra.ComponentHandle;
import io.knotra.ContextHandle;

/**
 * 受管 artifact 中一个工厂的类型化受控挂载句柄。
 *
 * <p>句柄代表活跃 artifact 视图而不是离线快照；drain 或卸载后即失效。宿主可以先
 * 通过 decoder 转换 raw 配置，再使用类型化 mount 提交组件；两条路径都会重新校验
 * 配置实例，防止 raw cast 绕过边界。</p>
 */
public interface ArtifactFactoryHandle<C> extends ArtifactFactoryCatalogEntry {

    /** artifact 发现时已通过共享合约校验的宿主/共享配置 token。 */
    Class<C> configType();

    /**
     * 把宿主持有的 raw 配置转换为工厂声明的配置类型。
     *
     * <p>decoder 输出为 null 或不是 {@link #configType()} 实例时立即失败。这个方法是
     * 活跃 artifact 视图；artifact 卸载后必须重新解析新的类型化句柄。</p>
     */
    C decodeConfig(Object rawConfig);

    /** 使用 decoder 的空输入挂载；无配置工厂会得到 NoConfig.INSTANCE。 */
    default ComponentHandle<C> mount(ContextHandle context, String mountId) {
        return mount(context, mountId, decodeConfig(null));
    }

    /** 挂载新的逻辑组件，并返回 Knotra 的稳定 ComponentHandle。 */
    ComponentHandle<C> mount(ContextHandle context, String mountId, C config);
}
