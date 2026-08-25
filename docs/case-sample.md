# 实战案例

本章通过完整的生产级业务案例，展示如何在真实业务场景中运用 Knotra 构建**零停机、秒级热替换、在途一致性保障与灰度隔离**的动态系统。

---

# 动态营销折扣引擎

## 业务场景与架构痛点

电商大促期间，营销策略需要根据实时的库存、大促阶段（预热、开门红、狂欢）快速调整规则：

- **不能重启应用**：大促期间并发流量巨大，重启应用会导致网关抖动、连接重连与在途请求丢失。
- **不能跨规则撕裂**：正在计算中的订单必须在一套完整的规则下算完，不能前半段执行满减规则 A，后半段执行分层规则 B。
- **概念澄清**：
  - **业务订单计算（Checkout）**：用户提交购物车并计算最终实付金额。
  - **运行时状态收敛（Settlement）**：Knotra 运行时完成新策略发布、在途调用排空与下游依赖同步的状态收敛。

## 1. 契约设计

在共享契约模块中定义业务模型与接口：

```java
public interface DiscountStrategy {
    Discount apply(Order order);
}

public interface CheckoutService {
    Receipt checkout(Order order);
}

public record Order(String orderId, String userId, int amount, String tier) {}
public record Discount(String ruleId, int amount, String description) {}
public record Receipt(String orderId, int payable, String discountRule) {}
```

定义显式命名的能力标识：

```java
public interface PromotionContracts {
    CapabilityKey<DiscountStrategy> CAMPAIGN =
            CapabilityKey.of("promotion.campaign", DiscountStrategy.class);
    CapabilityKey<DiscountStrategy> LOYALTY =
            CapabilityKey.of("promotion.loyalty", DiscountStrategy.class);
}
```

## 2. 策略实现

### 基础满减策略（v1）

```java
public record ThresholdDiscount(int threshold, int amount) implements DiscountStrategy {
    @Override
    public Discount apply(Order order) {
        return order.amount() >= threshold
                ? new Discount("threshold-" + threshold, amount, "满减优惠")
                : new Discount("none", 0, "无优惠");
    }
}
```

### 会员分层折扣策略（v2）

```java
public record TieredCampaignDiscount(Map<String, Integer> rates) implements DiscountStrategy {
    @Override
    public Discount apply(Order order) {
        int rate = rates.getOrDefault(order.tier(), 0);
        int amount = order.amount() * rate / 100;
        return new Discount("tiered-" + order.tier(), amount, "会员专属折扣");
    }
}
```

策略实现遵循纯函数设计原则：无内部可变状态、无阻塞 I/O。

## 3. 动态结算服务与装配

结算服务依赖 `DiscountStrategy`。通过 Knotra 的动态代理，当底层策略升级时，结算服务实例无需重启：

```java
public final class DynamicCheckoutService implements CheckoutService {
    private final DiscountStrategy campaign;

    public DynamicCheckoutService(DiscountStrategy campaign) {
        this.campaign = campaign;
    }

    @Override
    public Receipt checkout(Order order) {
        Discount discount = campaign.apply(order);
        int payable = Math.max(order.amount() - discount.amount(), 0);
        return new Receipt(order.orderId(), payable, discount.ruleId());
    }
}
```

使用 Beans DSL 定义并声明装配规则：

```java
var campaign = Beans.dynamic(PromotionContracts.CAMPAIGN); // 注入动态代理

BeanDefinition<DynamicCheckoutService> definition = Beans
        .component("dynamic-checkout")
        .with(campaign)
        .create(deps -> new DynamicCheckoutService(deps.get(campaign)))
        .provideAs(CheckoutService.class)
        .build();
```

## 4. 运行时发布与秒级热替换

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    // 1. 发布初始满减策略（满 300 减 30）
    PublicationChange<DiscountStrategy> initialChange = runtime.publish(
            PromotionContracts.CAMPAIGN,
            new ThresholdDiscount(300, 30));
    Publication<DiscountStrategy> campaign = initialChange.publication();
    initialChange.awaitSettled(Duration.ofSeconds(10));

    // 2. 挂载结算服务
    MountHandle checkout = definition.mount(runtime);
    checkout.requireActive(Duration.ofSeconds(10));

    // 3. 执行第 1 笔订单：计算结果应用满减规则
    CheckoutService service = runtime.require(CheckoutService.class);
    Receipt receipt1 = service.checkout(new Order("ORD-001", "U1", 400, "gold"));
    // 输出: ORD-001 应付: 370, 规则: threshold-300

    // 4. 秒级热替换为会员折扣（黄金会员打 85 折，优惠 15%）
    PublicationChange<DiscountStrategy> upgradeChange =
            campaign.update(new TieredCampaignDiscount(Map.of("gold", 15)));
    SettlementReport report = upgradeChange.awaitSettled(Duration.ofSeconds(10));

    if (report.hasFailedMounts()) {
        throw new IllegalStateException("策略升级失败: " + report.failedMounts());
    }

    // 5. 执行第 2 笔订单：无缝切换为会员分层规则，无需重启任何服务
    Receipt receipt2 = service.checkout(new Order("ORD-002", "U1", 400, "gold"));
    // 输出: ORD-002 应付: 340, 规则: tiered-gold
}
```

## 5. 进阶场景演进

### 场景一：多步骤计算一致性（锁定租约）

如果结算链路包含多个步骤（如先算折扣，再算赠品资格），要求整个请求期间策略不可变：

```java
public final class ConsistentCheckoutService {
    private final DynamicCapability<DiscountStrategy> campaignCapability;

    public ConsistentCheckoutService(DynamicCapability<DiscountStrategy> campaignCapability) {
        this.campaignCapability = campaignCapability;
    }

    public OrderResult checkout(Order order) {
        // 在 call 回调执行期间，策略版本严格锁定，即使外部发生 update 也不会在此次请求中漂移
        return campaignCapability.call(strategy -> {
            Discount discount = strategy.apply(order);
            boolean giftEligible = checkGift(strategy, order);
            return new OrderResult(discount, giftEligible);
        });
    }
}
```

### 场景二：批量结算 Job 绑定固定代际

对于离线跑批或批量账单结算 Job，整批处理必须使用同一代策略；若策略更新则触发任务重载：

```java
var campaign = Beans.fixed(PromotionContracts.CAMPAIGN); // 使用固定代际绑定

BeanDefinition<BatchSettlementJob> definition = Beans
        .component("batch-settlement")
        .with(campaign)
        .create(deps -> new BatchSettlementJob(deps.get(campaign)))
        .build();
```

提供方发生升级时，`BatchSettlementJob` 挂载点会自动进行安全销毁与重建。

### 场景三：双策略并行与独立升级

同时挂载大促折扣与积分抵扣：

```java
var campaign = Beans.dynamic(PromotionContracts.CAMPAIGN);
var loyalty = Beans.dynamic(PromotionContracts.LOYALTY);

BeanDefinition<CompositeCheckoutService> definition = Beans
        .component("composite-checkout")
        .with(campaign, loyalty)
        .create(deps -> new CompositeCheckoutService(deps.get(campaign), deps.get(loyalty)))
        .provideAs(CheckoutService.class)
        .build();
```

两个 `Publication` 槽位彼此独立，可分别单独热替换，互不干扰。

### 场景四：快速故障回滚机制

```java
DiscountStrategy currentStrategy = new ThresholdDiscount(300, 30);
DiscountStrategy newStrategy = new BrokenStrategy();

// 升级新版本
PublicationChange<DiscountStrategy> change = campaign.update(newStrategy);
SettlementReport report = change.awaitSettled(Duration.ofSeconds(10));

// 若升级后下游检测到异常，立即回滚至原策略
if (report.hasFailedMounts()) {
    campaign.update(currentStrategy).awaitSettled(Duration.ofSeconds(10));
}
```

### 场景五：VIP 租户灰度隔离（Context 树）

利用 Knotra 的 Context 树，可以为特定 VIP 租户发布专属折扣，而全局普通用户仍使用默认策略：

```java
// 1. 创建 VIP 专属子 Context
ContextHandle vipContext = runtime.advanced()
        .transact(tx -> tx.childContext(runtime.root(), "vip-tenant"))
        .value();

// 2. 仅在 VIP 上下文中发布专属大额折扣
runtime.publish(vipContext, PromotionContracts.CAMPAIGN, new TieredCampaignDiscount(Map.of("vip", 30)))
        .awaitSettled(Duration.ofSeconds(10));

// 3. VIP 用户通过 vipContext 视角访问，普通用户通过 root 视角访问
Receipt vipReceipt = vipContext.view().require(CheckoutService.class).checkout(vipOrder);
Receipt regularReceipt = runtime.require(CheckoutService.class).checkout(regularOrder);
```

---

# 多渠道支付网关动态路由

## 业务场景

平台接入支付宝、微信、银联等多个支付渠道。各渠道 SDK 升级频繁且需动态接入新渠道：

- 渠道组件以 PF4J 插件或独立模块形式部署。
- 宿主统一网关通过动态路由转发支付请求。
- 渠道插件升级或卸载时，在途支付请求平滑排空，旧 ClassLoader 完全回收。

```java
public interface PaymentGatewayRouter {
    ChargeResult routeAndCharge(String channel, ChargeRequest request);
}
```

使用 Knotra 结合 `ContributionRegistry` 或动态代理，新渠道挂载后立即在网关路由中可见，卸载后自动摘除，实现真正的业务不停机演进。
