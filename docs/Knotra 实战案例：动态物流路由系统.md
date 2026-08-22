# 实战案例：动态物流路由系统

> 通过物流包裹动态路由场景，演示从普通 POJO 装配、动态调用租约、PF4J 插件热升级到声明式期望树收敛的完整落地实践。

---

## 业务背景与技术挑战

在物流分拨中心，每天有数百万包裹进入各仓库。系统需要结合目的地、承运商运力以及各仓库定制规则，动态决定将包裹分派至顺丰、京东或中通等不同承运商。

```mermaid
graph LR
    P["待处理包裹 Parcel"] --> Q["持久化队列 ParcelInbox"]
    Q --> D["队列分发器 ParcelDispatcher"]
    D -->|根据规则计算路径| R["动态路由规则引擎 RoutePlanner"]
    R --> S["顺丰 / 京东 / 中通"]
```

核心工程诉求：
- **仓库隔离与多租户规则**：不同仓库需应用不同的路由规则。
- **规则在线热升级**：规则变更时应用不得重启。
- **在途任务安全保护**：已提交给旧规则计算的包裹必须安全完成，不得在执行中途强行中断或注销依赖。
- **类加载器安全回收**：高频热升级场景下，卸载的插件 ClassLoader 必须能被垃圾回收。

---

## 共享合约模块定义

宿主与插件共同依赖共享合约包 `com.acme.logistics.contract`：

```java
package com.acme.logistics.contract;

import io.knotra.CapabilityKey;
import java.util.concurrent.CompletionStage;

public interface RoutePlanner {
    CompletionStage<Route> plan(Parcel parcel);
}

public record Parcel(String id, String destination, double weightKg) {}
public record Route(String carrier, String serviceLevel, double estimatedCost) {}

public final class LogisticsContracts {
    public static final CapabilityKey<RoutePlanner> ROUTE_PLANNER =
            CapabilityKey.of("logistics.route-planner", RoutePlanner.class);
}
```

---

## 队列消费分发器实现

消费方组件通过动态代理持有路由规则能力，使得规则升级时分发器自身无需重启：

```java
package com.acme.logistics.core;

import com.acme.logistics.contract.*;
import io.knotra.beans.Beans;
import io.knotra.beans.BeanDefinition;
import io.knotra.NoConfig;

public final class ParcelDispatcher implements AutoCloseable {
    private final RoutePlanner routePlanner;

    public ParcelDispatcher(RoutePlanner routePlanner) {
        this.routePlanner = routePlanner;
    }

    public void onParcelReceived(Parcel parcel) {
        // 动态代理自动获取调用租约并穿透至当前可用提供方
        routePlanner.plan(parcel).thenAccept(route -> {
            System.out.printf("包裹 [%s] 成功路由至承运商: %s (%s)%n",
                    parcel.id(), route.carrier(), route.serviceLevel());
        });
    }

    @Override
    public void close() {
        System.out.println("分发器已安全停止。");
    }
}
```

装配分发器组件：

```java
BeanDefinition<NoConfig, ParcelDispatcher> dispatcherDef = Beans.component("dispatcher")
        .with(Beans.dynamicProxyRequired(LogisticsContracts.ROUTE_PLANNER))
        .create(ParcelDispatcher::new)
        .build();
```

---

## 规则插件开发（PF4J）

### 基础路由插件实现

```java
public final class StandardRoutePlanner implements RoutePlanner {
    @Override
    public CompletionStage<Route> plan(Parcel parcel) {
        return CompletableFuture.completedFuture(
                new Route("ZTO", "Standard-Ground", 12.5));
    }
}
```

### 智能路由插件升级版

```java
public final class SmartRoutePlanner implements RoutePlanner {
    @Override
    public CompletionStage<Route> plan(Parcel parcel) {
        if (parcel.weightKg() > 20.0) {
            return CompletableFuture.completedFuture(
                    new Route("SF-Express", "Heavy-Freight", 45.0));
        } else {
            return CompletableFuture.completedFuture(
                    new Route("JD-Logistics", "Next-Day-Air", 18.0));
        }
    }
}
```

---

## 宿主热升级与排空演练

```java
import io.knotra.*;
import io.knotra.beans.Beans;
import io.knotra.pf4j.*;
import java.nio.file.Path;
import java.util.Set;

public class LogisticsSystemDemo {
    public static void main(String[] args) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {

            // 挂载消费分发器
            ComponentHandle<NoConfig> dispatcher = Beans.mount(runtime, dispatcherDef);
            dispatcher.requireActive();

            try (Pf4jArtifactAdapter plugins = Pf4jArtifactAdapter.create(
                    Path.of("plugins"), runtime, Set.of("com.acme.logistics.contract"))) {

                // 加载并启用 V1 插件
                ArtifactSnapshot v1 = plugins.loadArtifact(Path.of("plugins/route-v1.jar"));

                ComponentHandle<NoConfig> routeHandle = plugins.factories()
                        .resolve("shanghai-router", NoConfig.class).orElseThrow()
                        .mount(runtime.root(), "active-router");
                routeHandle.requireActive();

                Parcel p1 = new Parcel("PKG-001", "北京", 25.0);
                runtime.root().view().require(LogisticsContracts.ROUTE_PLANNER).plan(p1);

                // 热升级为 V2 插件
                ArtifactSnapshot v2 = plugins.loadArtifact(Path.of("plugins/route-v2.jar"));

                // 卸载 V1 (自动排空在途请求后释放类加载器)
                plugins.unloadArtifact(v1.artifactId());

                // 挂载 V2
                plugins.factories()
                        .resolve("shanghai-smart-router", NoConfig.class).orElseThrow()
                        .mount(runtime.root(), "active-router");

                // 发送新包裹，自动路由至 V2
                Parcel p2 = new Parcel("PKG-002", "北京", 25.0);
                runtime.root().view().require(LogisticsContracts.ROUTE_PLANNER).plan(p2);
            }
        }
    }
}
```

---

## 架构价值总结

- **业务零侵入**：业务代码仅面向接口开发，不依赖任何 Knotra 专有类型。
- **无感平滑切换**：借助 `dynamicProxyRequired` 动态代理，消费方实例无需重启，方法调用自动穿透到最新提供方。
- **在途任务安全收敛**：插件卸载时通过租约排空机制保证已接收的任务安全完成，随后完整回收类加载器。
