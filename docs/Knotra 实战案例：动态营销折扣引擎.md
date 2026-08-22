# 实战案例：动态营销折扣引擎

> 以电商大促期间常见的“营销折扣与促销策略热插拔”为场景，演示如何通过 Knotra 实现业务零重启、策略即时生效、在途计算安全排空与插件级安全卸载。

---

## 业务背景与技术挑战

在电商结算中心，营销促销策略（如新人立减、双十一满减、VIP 会员阶梯折上折）由运营人员随时调整或按活动时间点自动切换。

```mermaid
graph LR
    O["订单结算请求 Order"] --> C["结算中心 CheckoutService"]
    C -->|动态代理调用| S["促销折扣策略 DiscountStrategy"]
    S --> R["折后应付金额 DiscountResult"]
```

### 核心痛点与诉求
- **严禁重启服务**：大促流量洪峰期间，重启结算服务会导致用户无法下单或连接超时。
- **业务消费方零感知**：结算服务（`CheckoutService`）属于核心长驻服务，策略升级时不应触发结算服务本身的销毁或重建。
- **在途计算防中断**：当新旧策略交替时，已经进入旧策略计算流程的在途订单必须安全完成，不能中途抛错。
- **第三方插件热插拔**：某些大型促销活动（如黑五专属策略）需要打包为独立 JAR 插件动态加载，活动结束后彻底卸载并释放 ClassLoader。

---

## 共享契约模块定义

共享合约模块（`contract`）仅包含接口、数据模型与能力 Key，由宿主工程与策略插件共同引用：

```java
package com.example.promotion.contract;

import io.knotra.CapabilityKey;

// 1. 业务数据模型
public record Order(String orderId, double originalAmount, String userTier) {}

public record DiscountResult(double originalAmount, double finalAmount, String strategyName) {}

// 2. 促销策略契约接口
public interface DiscountStrategy {
    DiscountResult applyDiscount(Order order);
}

// 3. 契约标识符常量
public final class PromotionContracts {
    // 促销策略能力 Key
    public static final CapabilityKey<DiscountStrategy> DISCOUNT_STRATEGY =
            CapabilityKey.of("promotion.discount-strategy", DiscountStrategy.class);

    // 结算服务自身对外暴露的能力 Key
    public static final CapabilityKey<CheckoutServiceContract> CHECKOUT_SERVICE =
            CapabilityKey.of("promotion.checkout-service", CheckoutServiceContract.class);
}
```

为了让结算服务保持面向接口编程，定义结算服务接口：

```java
package com.example.promotion.contract;

public interface CheckoutServiceContract {
    DiscountResult processCheckout(Order order);
}
```

---

## 结算消费方服务实现

结算服务实现 `CheckoutServiceContract` 并依赖 `DiscountStrategy`。通过注入 **`dynamicProxyRequired` 动态代理**，底层策略发生热替换或升级时，结算服务自身**保持在线且无需重启**：

```java
package com.example.promotion.core;

import com.example.promotion.contract.*;
import io.knotra.NoConfig;
import io.knotra.beans.BeanDefinition;
import io.knotra.beans.Beans;

// 纯 POJO 结算服务（零 Knotra 框架侵入）
public final class CheckoutService implements CheckoutServiceContract {
    private final DiscountStrategy discountStrategy; // 由 Knotra 注入动态代理

    public CheckoutService(DiscountStrategy discountStrategy) {
        this.discountStrategy = discountStrategy;
    }

    @Override
    public DiscountResult processCheckout(Order order) {
        // 动态代理自动获取调用租约并路由至当前最新的策略实现
        DiscountResult result = discountStrategy.applyDiscount(order);
        System.out.printf("订单 [%s] 原价: %.2f, 应付: %.2f (应用策略: %s)%n",
                order.orderId(), result.originalAmount(), result.finalAmount(), result.strategyName());
        return result;
    }
}

// 声明装配工厂：将动态代理注入构造函数，并将创建出的实例发布为 CHECKOUT_SERVICE
public final class CheckoutAssembly {
    public static BeanDefinition<NoConfig, CheckoutService> createDefinition() {
        return Beans.component("checkout-service")
                // 1. 注入动态依赖：底层提供方替换时，消费方不重启
                .with(Beans.dynamicProxyRequired(PromotionContracts.DISCOUNT_STRATEGY))
                // 2. 构造函数创建实例
                .create(CheckoutService::new)
                // 3. 对外发布装配好的结算能力
                .provide(PromotionContracts.CHECKOUT_SERVICE)
                .build();
    }
}
```

---

## 促销策略实现与演进

### 基础策略：全场 95 折（V1）

```java
package com.example.promotion.strategy;

import com.example.promotion.contract.DiscountResult;
import com.example.promotion.contract.DiscountStrategy;
import com.example.promotion.contract.Order;

public final class DefaultNineFiveDiscount implements DiscountStrategy {
    @Override
    public DiscountResult applyDiscount(Order order) {
        double finalPrice = order.originalAmount() * 0.95;
        return new DiscountResult(order.originalAmount(), finalPrice, "全场 95 折");
    }
}
```

### 进阶策略：满 200 减 50 阶梯促销（V2）

```java
package com.example.promotion.strategy;

import com.example.promotion.contract.DiscountResult;
import com.example.promotion.contract.DiscountStrategy;
import com.example.promotion.contract.Order;

public final class TieredThresholdDiscount implements DiscountStrategy {
    @Override
    public DiscountResult applyDiscount(Order order) {
        double original = order.originalAmount();
        double discount = (original >= 200.0) ? 50.0 : 0.0;
        double finalPrice = Math.max(0.0, original - discount);
        return new DiscountResult(original, finalPrice, "满 200 减 50 大促");
    }
}
```

---

## 运行时热替换与安全排空演练

完整端到端演示：挂载结算服务、在线替换促销策略、在途安全收敛与验证：

```java
package com.example.promotion.host;

import com.example.promotion.contract.*;
import com.example.promotion.core.CheckoutAssembly;
import com.example.promotion.strategy.DefaultNineFiveDiscount;
import com.example.promotion.strategy.TieredThresholdDiscount;
import io.knotra.*;
import io.knotra.beans.Beans;

public class PromotionEngineDemo {
    public static void main(String[] args) {
        try (KnotraRuntime runtime = KnotraRuntime.create()) {

            // 1. 发布初始策略 V1（全场 95 折）
            Provided<DiscountStrategy> strategyProvided = runtime.provide(
                    PromotionContracts.DISCOUNT_STRATEGY,
                    new DefaultNineFiveDiscount()
            );

            // 2. 挂载长驻结算服务（Knotra 自动完成依赖注入并发布 CHECKOUT_SERVICE）
            ComponentHandle<NoConfig> checkoutHandle = Beans.mount(
                    runtime, CheckoutAssembly.createDefinition());
            checkoutHandle.requireActive();

            // 3. 从 Knotra 容器直接获取已经装配完毕的结算服务实例（无需手动 new）
            CheckoutServiceContract checkoutService = runtime.root().view().require(
                    PromotionContracts.CHECKOUT_SERVICE);

            // 4. 处理首批订单（命中 V1 策略）
            Order order1 = new Order("ORD-001", 100.0, "REGULAR");
            checkoutService.processCheckout(order1);
            // 控制台输出: 订单 [ORD-001] 原价: 100.00, 应付: 95.00 (应用策略: 全场 95 折)

            // 5. 运行时热替换为 V2 策略（满 200 减 50），应用不重启
            System.out.println(">>> [活动开始] 正在热切换为大促满减策略...");
            Provided<DiscountStrategy> v2 = strategyProvided.replace(new TieredThresholdDiscount());

            // 等待依赖收敛就绪（底层在途任务安全排空）
            v2.whenSettled().toCompletableFuture().join();

            // 6. 再次处理订单（无缝命中 V2 策略，CheckoutService 实例未发生任何重启）
            Order order2 = new Order("ORD-002", 300.0, "VIP");
            checkoutService.processCheckout(order2);
            // 控制台输出: 订单 [ORD-002] 原价: 300.00, 应付: 250.00 (应用策略: 满 200 减 50 大促)

            System.out.println(">>> 促销策略热替换成功，结算服务全程无中断。");
        }
    }
}
```

---

## 架构价值总结

- **业务代码零污染**：`CheckoutService` 仅面向纯 Java 接口编程，无框架注解或硬编码依赖。
- **容器自动装配托管**：通过 `Beans.component`，Knotra 自动完成依赖查找、代理注入与生命周期管理，业务使用时直接通过 `runtime.root().view().require(...)` 获取。
- **无感平滑切换**：通过 `dynamicProxyRequired` 动态代理，提供方升级时消费方保持在线，方法调用自动穿透到最新实现。
- **在途任务安全保护**：策略替换时，Knotra 通过租约排空机制等待旧策略上的在途计算安全返回，避免产生脏数据或中途报错。
