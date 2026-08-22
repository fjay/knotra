# 实战案例：动态营销折扣引擎

场景：电商大促期间，折扣规则需要根据活动阶段快速调整；结算链路不能重启，正在计算的订单不能跨新旧规则混用。这个案例展示如何用 Publication 管理策略槽位、用动态代理保持结算服务在线，以及如何区分 fixed dependency 与 dynamic dependency。

## 合约设计

共享业务合约：

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

策略是多实现、多阶段并存的场景，显式命名比 `Class<T>` 默认 key 更清楚：

```java
public interface PromotionContracts {
    CapabilityKey<DiscountStrategy> CAMPAIGN =
            CapabilityKey.of("promotion.campaign", DiscountStrategy.class);
    CapabilityKey<DiscountStrategy> LOYALTY =
            CapabilityKey.of("promotion.loyalty", DiscountStrategy.class);
}
```

## 策略实现

基础满减：

```java
public record ThresholdDiscount(int threshold, int amount) implements DiscountStrategy {
    @Override
    public Discount apply(Order order) {
        return order.amount() >= threshold
                ? new Discount("threshold-" + threshold, amount, "满减")
                : new Discount("none", 0, "无折扣");
    }
}
```

会员分层叠加券：

```java
public record TieredCampaignDiscount(Map<String, Integer> rates) implements DiscountStrategy {
    @Override
    public Discount apply(Order order) {
        int rate = rates.getOrDefault(order.tier(), 0);
        int amount = order.amount() * rate / 100;
        return new Discount("tiered-" + order.tier(), amount, "会员折扣");
    }
}
```

策略对象保持纯函数形态：不做 I/O、不保存订单状态、不启动线程。

## 结算服务

结算服务对底层策略使用动态代理。策略替换时结算服务实例不重建：

```java
public final class DynamicCheckoutService implements CheckoutService {

    private final DiscountStrategy campaign;

    public DynamicCheckoutService(DiscountStrategy campaign) {
        this.campaign = campaign;
    }

    @Override
    public Receipt checkout(Order order) {
        Discount discount = campaign.apply(order);
        return new Receipt(
                order.orderId(),
                Math.max(order.amount() - discount.amount(), 0),
                discount.ruleId());
    }
}
```

定义并挂载：

```java
BeanDefinition<DynamicCheckoutService> definition = Beans
        .component("dynamic-checkout")
        .with(Beans.dynamic(PromotionContracts.CAMPAIGN))
        .create(DynamicCheckoutService::new)
        .provideAs(CheckoutService.class, service -> service)
        .build();
```

如果某类结算必须在一次请求里读取多个数据并固定同一代策略，注入 `DynamicCapability<DiscountStrategy>` 并用 `call` 包住整个计算：

```java
Quote quote = capability.call(current -> new Quote(
        current.apply(order),
        inventory.reserve(order),
        risk.evaluate(order)));
```

## 发布与热替换

```java
try (KnotraRuntime runtime = KnotraRuntime.create()) {
    PublicationChange<DiscountStrategy> initial = runtime.publish(
            PromotionContracts.CAMPAIGN,
            new ThresholdDiscount(300, 30));
    Publication<DiscountStrategy> campaign = initial.publication();

    MountHandle checkout = definition.mount(runtime);
    checkout.requireActive(Duration.ofSeconds(10));

    var view = runtime.root().view();
    Receipt before = view.require(CheckoutService.class)
            .checkout(new Order("A1", "u1", 400, "gold"));

    PublicationChange<DiscountStrategy> upgraded =
            campaign.update(new TieredCampaignDiscount(Map.of("gold", 15)));
    SettlementReport report = upgraded.awaitSettled(Duration.ofSeconds(10));

    if (report.hasFailedMounts()) {
        throw new IllegalStateException(report.failedMounts().toString());
    }

    checkout.requireActive(Duration.ofSeconds(10));
    Receipt after = view.require(CheckoutService.class)
            .checkout(new Order("A2", "u1", 400, "gold"));
}
```

结果：

```text
A1 payable=370 rule=threshold-300
A2 payable=340 rule=tiered-gold
```

`report.allAffectedActive()` 可能为 false，因为动态代理结算服务不需要重建，影响集可能为空。这不是故障；确认具体结算服务可用要用 `checkout.requireActive(Duration.ofSeconds(10))`。

## 固定代际消费方


订单风控或批量计费如果必须保证整批订单使用同一策略，应使用 fixed dependency：

```java
BeanDefinition<BatchSettlementJob> definition = Beans
        .component("batch-settlement")
        .with(Beans.fixed(PromotionContracts.CAMPAIGN))
        .create(BatchSettlementJob::new)
        .build();
```


提供方替换时，该 Job 会绑定新的 Registration 并重建。旧 Activation 中已经开始的批次继续使用旧代际，完成并 drain 后释放。

选择规则：

- 单次方法调用或无状态路由：`dynamic`。
- 一次回调中多个观察必须一致：`dynamicCapability`。
- 一个业务对象整个生命周期都要绑定一代：`required`。
- 提供方可有可无且消费方可独立工作：`optional` / `dynamicOptional`。

## 双策略并行

活动与积分使用两个槽位：

```java
BeanDefinition<CompositeCheckoutService> definition = Beans
        .component("composite-checkout")
        .with(Beans.dynamic(PromotionContracts.CAMPAIGN))
        .with(Beans.dynamic(PromotionContracts.LOYALTY))
        .create(CompositeCheckoutService::new)
        .provideAs(CheckoutService.class, service -> service)
        .build();
```

两个 Publication 独立更新，各自返回自己的 `PublicationChange`。不要保存第一次 change 再拿它更新第二次策略；长期槽位对象是 `Publication<T>`。

## 回滚策略

发布新策略前保留当前策略对象：

```java
DiscountStrategy previous = currentStrategy;
PublicationChange<DiscountStrategy> change = campaign.update(next);
SettlementReport report = change.awaitSettled(Duration.ofSeconds(30));

if (report.hasFailedMounts() && cannotTolerateFailure) {
    campaign.update(previous).awaitSettled(Duration.ofSeconds(30));
}
```

回滚也是一次新的代际，不是恢复旧 Registration 身份。监控上应记录 generation 和失败 outcome，而不是只记录“update 方法返回”。

## 灰度与 Context

不同租户可以使用子 Context 遮蔽全局策略：

```java
ContextHandle vip = runtime.advanced()
        .transact(transaction -> transaction.childContext(runtime.root(), "vip"))
        .value();

runtime.publish(vip, PromotionContracts.CAMPAIGN, new TieredCampaignDiscount(vipRates))
        .awaitSettled(Duration.ofSeconds(10));

Receipt receipt = vip.view().require(CheckoutService.class).checkout(order);
```

在 vip Context 下挂载的结算服务看到 vip 策略；根 Context 服务仍使用全局策略。Context 释放后其中的 Publication 进入 `DISPLACED`，不会静默回落为可更新槽位。

## 运行检查

大促平台建议监控：

- 每次 update 的 settlement generation 与耗时。
- `hasFailedMounts()` 及失败挂载 ID。
- 动态代理调用失败率，特别是 provider 缺失。
- fixed 消费方重建次数。
- 策略对象池和订单线程是否持有旧策略强引用。
- 灰度 Context 数量和释放状态。

核心收益不是少写几行装配代码，而是把“策略代际、消费方重建、在途调用 drain”变成显式可观测的运行时行为。
