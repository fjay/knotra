# 实战案例：动态物流路由系统

> **面向读者**：本文通过一个完整的**“物流包裹动态路由系统”**案例，带您一步步走完从普通 POJO 装配、动态调用租约、PF4J 插件热升级到声明式期望树收敛的完整落地流程。

---

## 业务背景与面临的挑战

在一个现代物流分拨中心，每天有数百万包裹进入各仓库。系统需要根据包裹的目的地、承运商运力以及各仓库的特定规则，动态决定将包裹交给顺丰、京东还是中通配送。

```mermaid
graph LR
    P["待处理包裹 Parcel"] --> Q["持久化队列 ParcelInbox"]
    Q --> D["队列分发器 ParcelDispatcher"]
    D -->|根据规则计算最优路径| R["动态路由规则引擎 RoutePlanner"]
    R --> S["顺丰 / 京东 / 中通"]
```

### 真实生产中的痛点
- **上海仓与深圳仓规则不同**：需要按仓库（多租户/Context）隔离路由规则。
- **规则每周热升级**：升级为 V2 规则时，应用不能重启。
- **在途包裹不能丢**：已经分发给旧规则处理中的包裹，必须等待其计算完毕；不能在方法执行中途把旧规则实例暴力销毁。
- **插件卸载 ClassLoader 必须回收**：长期多次热升级，Metaspace 内存不能增长。

---

## 定义共享合约包

宿主应用和插件工程共同依赖此接口包：

```java
package com.acme.logistics.contract;

import io.knotra.CapabilityKey;
import java.util.concurrent.CompletionStage;

// 核心路由规划接口（可替换的插件能力）
public interface RoutePlanner {
    CompletionStage<Route> plan(Parcel parcel);
}

// 包裹与路由数据模型
public record Parcel(String id, String destination, double weightKg) {}
public record Route(String carrier, String serviceLevel, double estimatedCost) {}

// 契约常量
public final class LogisticsContracts {
    public static final CapabilityKey<RoutePlanner> ROUTE_PLANNER =
            CapabilityKey.of("logistics.route-planner", RoutePlanner.class);
}
```

---

## 编写队列消费分发器

分发器从队列获取包裹，并调用动态注入的 `RoutePlanner` 计算路径。我们使用**动态代理（Dynamic Proxy）**，使得路由规则热升级时，分发器无需重启：

```java
package com.acme.logistics.core;

import com.acme.logistics.contract.*;
import io.knotra.beans.Beans;
import io.knotra.beans.BeanDefinition;
import io.knotra.NoConfig;

public final class ParcelDispatcher implements AutoCloseable {
    private final RoutePlanner routePlanner; // 这是一个动态代理

    public ParcelDispatcher(RoutePlanner routePlanner) {
        this.routePlanner = routePlanner;
    }

    public void onParcelReceived(Parcel parcel) {
        // 安全调用：自动获取租约，底层即使发生热替换也能优雅收敛
        routePlanner.plan(parcel).thenAccept(route -> {
            System.out.printf("包裹 [%s] 成功路由至承运商: %s (%s)%n",
                    parcel.id(), route.carrier(), route.serviceLevel());
        });
    }

    @Override
    public void close() {
        System.out.println("分发器已安全停止接单。");
    }
}
```

使用 Beans DSL 装配消费方组件：
```java
BeanDefinition<NoConfig, ParcelDispatcher> dispatcherDef = Beans.component("dispatcher")
        // 声明动态代理依赖：Provider 替换时消费方不重启
        .with(Beans.dynamicProxyRequired(LogisticsContracts.ROUTE_PLANNER))
        .create(ParcelDispatcher::new)
        .build();
```

---

## 开发规则插件（PF4J）

### V1 规则插件实现（优先走经济型陆运）

```java
public final class StandardRoutePlanner implements RoutePlanner {
    @Override
    public CompletionStage<Route> plan(Parcel parcel) {
        return CompletableFuture.completedFuture(
                new Route("ZTO", "Standard-Ground", 12.5));
    }
}
```

### V2 规则插件实现（升级为智能算法：重货走顺丰，轻货走京东）

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

## 宿主运行时热升级与安全排空演练

```java
import io.knotra.*;
import io.knotra.beans.Beans;
import io.knotra.pf4j.*;
import java.nio.file.Path;
import java.util.Set;

public class LogisticsSystemDemo {
    public static void main(String[] args) throws Exception {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {

            // 挂载队列分发器组件
            ComponentHandle<NoConfig> dispatcher = Beans.mount(runtime, dispatcherDef);
            dispatcher.requireActive();

            try (Pf4jArtifactAdapter plugins = Pf4jArtifactAdapter.create(
                    Path.of("plugins"), runtime, Set.of("com.acme.logistics.contract"))) {

                // 加载并启用 V1 路由插件
                System.out.println(">>> [发布 V1] 加载上海仓 V1 路由插件...");
                ArtifactSnapshot v1 = plugins.loadArtifact(Path.of("plugins/route-v1.jar"));

                ComponentHandle<NoConfig> routeHandle = plugins.factories()
                        .resolve("shanghai-router", NoConfig.class).orElseThrow()
                        .mount(runtime.root(), "active-router");
                routeHandle.requireActive();

                // 模拟业务处理
                Parcel p1 = new Parcel("PKG-001", "北京", 25.0);
                runtime.root().view().require(LogisticsContracts.ROUTE_PLANNER).plan(p1);
                // 输出: 包裹 [PKG-001] 成功路由至承运商: ZTO (Standard-Ground)

                // 热升级至 V2 智能插件
                System.out.println(">>> [热升级 V2] 正在无感替换为 V2 插件...");
                ArtifactSnapshot v2 = plugins.loadArtifact(Path.of("plugins/route-v2.jar"));

                // 卸载 V1（自动安全排空旧在途任务，再释放 ClassLoader）
                plugins.unloadArtifact(v1.artifactId());

                // 挂载 V2
                plugins.factories()
                        .resolve("shanghai-smart-router", NoConfig.class).orElseThrow()
                        .mount(runtime.root(), "active-router");

                // 再次发送包裹，无缝路由到新规则
                Parcel p2 = new Parcel("PKG-002", "北京", 25.0);
                runtime.root().view().require(LogisticsContracts.ROUTE_PLANNER).plan(p2);
                // 输出: 包裹 [PKG-002] 成功路由至承运商: SF-Express (Heavy-Freight)

                System.out.println(">>> 热升级演练圆满完成，业务全程零中断！");
            }
        }
    }
}
```

---

## 核心收获总结

通过本案例，我们验证了 Knotra 的核心生产价值：
- **业务解耦**：业务代码（`ParcelDispatcher`）不依赖 Knotra API，只面向业务接口编程；
- **零停机平滑热更**：通过 `dynamicProxyRequired` 动态代理，消费方不需要重启，每次调用自动穿透到最新 Provider；
- **在途任务安全保护**：卸载 V1 插件时，通过租约排空（Drain）机制，保证已进入 V1 的包裹计算完毕后再注销 ClassLoader。
