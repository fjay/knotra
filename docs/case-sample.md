# 实战案例

本章通过 5 个涵盖常见业务形态的生产级端到端实战案例，帮助新人与架构师深入理解 Knotra 的各项核心特性：

- **[案例一：动态营销折扣引擎](#案例一动态营销折扣引擎)**：无状态策略秒级热替换、跨步骤租约一致性与故障秒级回滚
- **[案例二：多渠道支付网关与租户灰度](#案例二多渠道支付网关与租户灰度)**：多策略动态聚合、Context 树多租户/灰度隔离与在途无损下线
- **[案例三：数据批处理与生命周期 LIFO 释放](#案例三数据批处理与生命周期-lifo-释放)**：固定代际（`fixed`）自动重建、混合依赖装配与资源逆序清理
- **[案例四：Spring Boot 宿主调用动态风控插件](#案例四spring-boot-宿主调用动态风控插件)**：`SpringDynamicBridge` 宿主桥接与 Spring 子容器隔离
- **[案例五：事件驱动订单流水线](#案例五事件驱动订单流水线)**：进程内事件总线（`Sync`、`Parallel`、`Waterfall`、`Bail`）与动态订阅排空

---

# 案例一：动态营销折扣引擎

## 业务背景与痛点

在电商大促（如双 11、618）期间，运营团队需要根据各时段的销量与库存实时调整促销策略（例如：预热期满 300 减 30，高潮期黄金会员 85 折，尾声期加赠积分）：

1. **不可重启应用**：大促流量高峰期重启服务会导致连接抖动、网关重试风暴与在途订单丢失。
2. **在途计算防撕裂**：同一笔订单在计算“折扣金额”与“赠品资格”两个连续步骤时，必须保证使用的是同一版本的策略规则，不能发生中途漂移。
3. **故障秒级回滚**：新发布的策略如果存在逻辑缺陷或引发资损，必须在秒级内无缝回滚到稳定版本。

## 完整代码实现

### 1. 业务契约与实体定义

```java
package io.knotra.demo.discount;

import io.knotra.CapabilityKey;
import java.util.Map;

// 1. 业务实体
public record Order(String orderId, String userId, int amount, String tier) {}
public record Discount(String ruleId, int discountAmount, String description) {}
public record Receipt(String orderId, int originalAmount, int payableAmount, String discountRule, boolean giftEligible) {}

// 2. 折扣策略契约接口
public interface DiscountStrategy {
    Discount calculateDiscount(Order order);
    boolean isGiftEligible(Order order);
}

// 3. 结算服务契约接口
public interface CheckoutService {
    Receipt checkout(Order order);
}

// 4. 强类型能力标识定义
public interface PromotionContracts {
    CapabilityKey<DiscountStrategy> CAMPAIGN =
            CapabilityKey.of("promotion.campaign", DiscountStrategy.class);
}
```

### 2. 策略实现（多版本）

```java
// 策略 v1：基础满减规则（满 threshold 减 amount，满 200 赠送礼品）
public record ThresholdDiscount(int threshold, int amount) implements DiscountStrategy {
    @Override
    public Discount calculateDiscount(Order order) {
        return order.amount() >= threshold
                ? new Discount("threshold-" + threshold, amount, "满减优惠")
                : new Discount("none", 0, "无优惠");
    }

    @Override
    public boolean isGiftEligible(Order order) {
        return order.amount() >= 200;
    }
}

// 策略 v2：会员分层折扣规则（不同会员等级享受不同折扣比例，黄金会员送礼品）
public record TieredDiscount(Map<String, Integer> discountPercentages) implements DiscountStrategy {
    @Override
    public Discount calculateDiscount(Order order) {
        int rate = discountPercentages.getOrDefault(order.tier(), 0);
        int discountAmount = order.amount() * rate / 100;
        return new Discount("tiered-" + order.tier(), discountAmount, "会员专属折扣");
    }

    @Override
    public boolean isGiftEligible(Order order) {
        return "gold".equalsIgnoreCase(order.tier());
    }
}
```

### 3. 结算服务装配（支持多步骤租约锁定）

```java
import io.knotra.DynamicCapability;
import io.knotra.beans.BeanDefinition;
import io.knotra.beans.Beans;

public final class ConsistentCheckoutService implements CheckoutService {
    // 注入显式租约句柄，用于多步骤一致性保护
    private final DynamicCapability<DiscountStrategy> campaignCap;

    public ConsistentCheckoutService(DynamicCapability<DiscountStrategy> campaignCap) {
        this.campaignCap = campaignCap;
    }

    @Override
    public Receipt checkout(Order order) {
        // 使用 call 锁定单次请求内的策略版本，即使在执行中发生 update 也不会版本漂移
        return campaignCap.call(strategy -> {
            Discount discount = strategy.calculateDiscount(order);
            int payable = Math.max(order.amount() - discount.discountAmount(), 0);
            boolean gift = strategy.isGiftEligible(order);

            return new Receipt(
                    order.orderId(),
                    order.amount(),
                    payable,
                    discount.ruleId(),
                    gift
            );
        });
    }
}
```

### 4. 运行与秒级热替换实战

```java
package io.knotra.demo.discount;

import io.knotra.*;
import io.knotra.beans.BeanDefinition;
import io.knotra.beans.Beans;
import java.time.Duration;
import java.util.Map;

public class DiscountEngineDemo {

    public static void main(String[] args) {
        Duration timeout = Duration.ofSeconds(10);

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            // 步骤 1：发布初始策略 v1（满 300 减 30）
            PublicationChange<DiscountStrategy> initChange = runtime.publish(
                    PromotionContracts.CAMPAIGN,
                    new ThresholdDiscount(300, 30));
            Publication<DiscountStrategy> campaignSlot = initChange.publication();
            initChange.awaitSettled(timeout);

            // 步骤 2：使用 Beans DSL 声明并挂载结算服务
            var campaignDep = Beans.dynamicCapability(PromotionContracts.CAMPAIGN);
            BeanDefinition<ConsistentCheckoutService> definition = Beans
                    .component("consistent-checkout")
                    .with(campaignDep)
                    .create(deps -> new ConsistentCheckoutService(deps.get(campaignDep)))
                    .provideAs(CheckoutService.class)
                    .build();

            MountHandle checkoutHandle = definition.mount(runtime);
            checkoutHandle.requireActive(timeout);

            CheckoutService checkoutService = runtime.require(CheckoutService.class);

            // 步骤 3：处理第 1 笔订单（执行 v1 规则）
            Order order1 = new Order("ORD-001", "USER-101", 400, "gold");
            Receipt receipt1 = checkoutService.checkout(order1);
            System.out.println("【订单 1 (v1 规则)】: " + receipt1);
            // 输出: 应付 370 (满300减30), 赠品: true

            // 步骤 4：秒级热替换为 v2 规则（黄金会员 85 折即优惠 15%），服务零重启
            PublicationChange<DiscountStrategy> upgradeChange =
                    campaignSlot.update(new TieredDiscount(Map.of("gold", 15, "silver", 10)));
            SettlementReport report = upgradeChange.awaitSettled(timeout);

            if (report.hasFailedMounts()) {
                throw new IllegalStateException("策略升级失败: " + report.failedMounts());
            }

            // 步骤 5：处理第 2 笔订单（透明执行 v2 规则）
            Order order2 = new Order("ORD-002", "USER-102", 400, "gold");
            Receipt receipt2 = checkoutService.checkout(order2);
            System.out.println("【订单 2 (v2 规则)】: " + receipt2);
            // 输出: 应付 340 (85折优惠60), 赠品: true

            // 步骤 6：故障秒级回滚至 v1
            System.out.println("--- 触发紧急故障回滚 ---");
            campaignSlot.update(new ThresholdDiscount(300, 30)).awaitSettled(timeout);

            Receipt receipt3 = checkoutService.checkout(order2);
            System.out.println("【订单 3 (回滚后)】: " + receipt3);
            // 输出: 应付 370 (恢复满300减30)
        }
    }
}
```

---

# 案例二：多渠道支付网关与租户灰度

## 业务背景与痛点

聚合支付平台接入微信、支付宝、银联等多个渠道：
1. **多渠道动态注册**：新开通银行渠道或第三方钱包时，渠道模块需动态挂载，无需重启网关。
2. **VIP / 灰度租户隔离**：大客户或测试租户需要使用专属的高费率/低延时通道，普通用户继续走标准通道。
3. **老渠道平滑下线**：废弃某个老渠道时，排空在途交易后再彻底释放。

## 完整代码实现

### 1. 支付契约与渠道接口

```java
package io.knotra.demo.payment;

import io.knotra.CapabilityKey;

public record PaymentRequest(String traceId, String tenantId, int amount, String channel) {}
public record PaymentResponse(String traceId, boolean success, String channel, String message) {}

public interface PaymentChannel {
    String channelCode();
    PaymentResponse pay(PaymentRequest request);
}

public interface PaymentGateway {
    PaymentResponse routeAndPay(PaymentRequest request);
}
```

### 2. 各渠道实现

```java
public record AlipayChannel(String merchantId) implements PaymentChannel {
    @Override
    public String channelCode() { return "alipay"; }

    @Override
    public PaymentResponse pay(PaymentRequest request) {
        return new PaymentResponse(request.traceId(), true, "alipay", "支付宝支付成功[商户:" + merchantId + "]");
    }
}

public record WechatPayChannel(String appId) implements PaymentChannel {
    @Override
    public String channelCode() { return "wechat"; }

    @Override
    public PaymentResponse pay(PaymentRequest request) {
        return new PaymentResponse(request.traceId(), true, "wechat", "微信支付成功[App:" + appId + "]");
    }
}
```

### 3. 动态聚合路由网关

```java
import io.knotra.DynamicCapability;
import java.util.List;

public final class SmartPaymentGateway implements PaymentGateway {
    private final DynamicCapability<PaymentChannel> alipay;
    private final DynamicCapability<PaymentChannel> wechat;

    public SmartPaymentGateway(
            DynamicCapability<PaymentChannel> alipay,
            DynamicCapability<PaymentChannel> wechat) {
        this.alipay = alipay;
        this.wechat = wechat;
    }

    @Override
    public PaymentResponse routeAndPay(PaymentRequest request) {
        return switch (request.channel().toLowerCase()) {
            case "alipay" -> alipay.call(channel -> channel.pay(request));
            case "wechat" -> wechat.call(channel -> channel.pay(request));
            default -> new PaymentResponse(request.traceId(), false, request.channel(), "未支持的支付渠道");
        };
    }
}
```

### 4. 运行与 Context 树租户灰度实战

```java
package io.knotra.demo.payment;

import io.knotra.*;
import io.knotra.beans.BeanDefinition;
import io.knotra.beans.Beans;
import java.time.Duration;

public class PaymentGatewayDemo {

    public static final CapabilityKey<PaymentChannel> ALIPAY_KEY =
            CapabilityKey.of("channel.alipay", PaymentChannel.class);
    public static final CapabilityKey<PaymentChannel> WECHAT_KEY =
            CapabilityKey.of("channel.wechat", PaymentChannel.class);

    public static void main(String[] args) {
        Duration timeout = Duration.ofSeconds(10);

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            // 1. 全局根上下文发布标准渠道
            runtime.publish(ALIPAY_KEY, new AlipayChannel("standard-alipay-001")).awaitSettled(timeout);
            runtime.publish(WECHAT_KEY, new WechatPayChannel("standard-wechat-001")).awaitSettled(timeout);

            // 2. 挂载聚合网关
            var alipayDep = Beans.dynamicCapability(ALIPAY_KEY);
            var wechatDep = Beans.dynamicCapability(WECHAT_KEY);
            BeanDefinition<SmartPaymentGateway> definition = Beans
                    .component("smart-payment-gateway")
                    .with(alipayDep, wechatDep)
                    .create(deps -> new SmartPaymentGateway(deps.get(alipayDep), deps.get(wechatDep)))
                    .provideAs(PaymentGateway.class)
                    .build();

            definition.mount(runtime).requireActive(timeout);

            // 3. 普通租户发起支付（走根上下文）
            PaymentGateway globalGateway = runtime.require(PaymentGateway.class);
            PaymentResponse res1 = globalGateway.routeAndPay(
                    new PaymentRequest("T-001", "normal-tenant", 100, "alipay"));
            System.out.println("【普通租户支付】: " + res1.message());

            // 4. 创建 VIP 租户子 Context，并局部覆盖（Shadowing）支付宝渠道为 VIP 专线
            ContextHandle vipContext = runtime.advanced().transact(tx ->
                    tx.childContext(runtime.root(), "vip-tenant-context")
            ).value();

            // 在 VIP 上下文中发布专属大商户号
            runtime.publish(vipContext, ALIPAY_KEY, new AlipayChannel("vip-exclusive-999"))
                    .awaitSettled(timeout);

            // 5. VIP 视角与全局视角分别调用
            PaymentGateway vipGateway = vipContext.view().require(PaymentGateway.class);

            PaymentResponse vipRes = vipGateway.routeAndPay(
                    new PaymentRequest("T-002", "vip-tenant", 5000, "alipay"));
            System.out.println("【VIP 租户支付】: " + vipRes.message());
            // 输出: 支付宝支付成功[商户:vip-exclusive-999]

            PaymentResponse normalRes = globalGateway.routeAndPay(
                    new PaymentRequest("T-003", "normal-tenant", 100, "alipay"));
            System.out.println("【普通租户再次支付】: " + normalRes.message());
            // 输出: 支付宝支付成功[商户:standard-alipay-001]
        }
    }
}
```

---

# 案例三：数据批处理与生命周期 LIFO 释放

## 业务背景与痛点

对于离线数据清洗或账单批处理 Job：
1. **必须绑定固定版本（`Beans.fixed`）**：Job 启动后必须使用固定版本的数据源与规则，中途底层升级时，Job 必须被安全停止、完成当前批次排空，并自动按新版本重新创建。
2. **混合依赖装配**：Job 内部的数据源是固定代际依赖（`fixed`），而监控告警客户端是动态代理依赖（`dynamic`）。
3. **确定性逆序释放（LIFO）**：组件停止时，必须先关闭消费工作线程，再关闭底层数据库连接池，否则会导致写盘异常。

## 完整代码实现

```java
package io.knotra.demo.batch;

import io.knotra.*;
import io.knotra.beans.BeanDefinition;
import io.knotra.beans.Beans;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

// 1. 模拟数据库连接池与监控客户端
public interface DataSource extends AutoCloseable {
    String executeQuery(String sql);
}

public interface MetricsClient {
    void reportMetric(String name, long value);
}

public class MockDataSource implements DataSource {
    private final String url;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public MockDataSource(String url) {
        this.url = url;
    }

    @Override
    public String executeQuery(String sql) {
        if (closed.get()) {
            throw new IllegalStateException("连接池已关闭，无法执行 SQL: " + sql);
        }
        return "Result from [" + url + "] for " + sql;
    }

    @Override
    public void close() {
        closed.set(true);
        System.out.println("-> [DataSource] 连接池关闭: " + url);
    }
}

// 2. 批处理 Job 实现
public final class BatchProcessorJob implements AutoCloseable {
    private final DataSource dataSource;
    private final MetricsClient metrics;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public BatchProcessorJob(DataSource dataSource, MetricsClient metrics) {
        this.dataSource = dataSource;
        this.metrics = metrics;
    }

    public void processBatch(String batchId) {
        String data = dataSource.executeQuery("SELECT * FROM batch WHERE id = " + batchId);
        metrics.reportMetric("batch.processed", 1);
        System.out.println("处理批次: " + data);
    }

    @Override
    public void close() {
        running.set(false);
        System.out.println("-> [BatchProcessorJob] 停止批处理工作线程");
    }
}
```

### 3. 声明装配与代际更新重建演示

```java
public class BatchJobDemo {

    public static void main(String[] args) {
        Duration timeout = Duration.ofSeconds(10);

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            // 发布数据源 v1 与监控客户端
            PublicationChange<DataSource> dsChange = runtime.publish(
                    DataSource.class, new MockDataSource("jdbc:mysql://primary-db:3306/db"));
            Publication<DataSource> dsSlot = dsChange.publication();
            dsChange.awaitSettled(timeout);

            runtime.publish(MetricsClient.class, (name, val) ->
                    System.out.println("[Metric 上报] " + name + " = " + val)).awaitSettled(timeout);

            // 混合依赖装配：DataSource 使用 fixed 绑定，Metrics 使用 dynamic 代理
            var dsDep = Beans.fixed(DataSource.class);
            var metricsDep = Beans.dynamic(MetricsClient.class);

            BeanDefinition<BatchProcessorJob> jobDef = Beans
                    .component("batch-job")
                    .with(dsDep, metricsDep)
                    .create(deps -> new BatchProcessorJob(deps.get(dsDep), deps.get(metricsDep)))
                    .provideAs(BatchProcessorJob.class)
                    .build();

            MountHandle jobHandle = jobDef.mount(runtime);
            jobHandle.requireActive(timeout);

            // 执行批次
            BatchProcessorJob job = runtime.require(BatchProcessorJob.class);
            job.processBatch("BATCH-20260801");

            // 升级数据源到读写分离集群 v2
            System.out.println("--- 升级底层数据源 ---");
            PublicationChange<DataSource> upgradeChange =
                    dsSlot.update(new MockDataSource("jdbc:mysql://cluster-db:3306/db"));
            SettlementReport report = upgradeChange.awaitSettled(timeout);

            // 验证：由于 BatchProcessorJob 是 fixed 依赖，它会自动先安全关闭旧实例（触发 close()），再使用新数据源重建
            System.out.println("受影响重建的挂载点数量: " + report.affectedMounts().size());

            // 获取重建后的 Job 执行新批次
            BatchProcessorJob newJob = runtime.require(BatchProcessorJob.class);
            newJob.processBatch("BATCH-20260802");
        }
    }
}
```

控制台关闭顺序展示（先停 Job，再关 DataSource）：
```text
--- 升级底层数据源 ---
-> [BatchProcessorJob] 停止批处理工作线程
-> [DataSource] 连接池关闭: jdbc:mysql://primary-db:3306/db
受影响重建的挂载点数量: 1
处理批次: Result from [jdbc:mysql://cluster-db:3306/db] for SELECT * FROM batch WHERE id = BATCH-20260802
```

---

# 案例四：Spring Boot 宿主调用动态风控插件

## 业务背景与痛点

传统 Spring Boot 单例应用中，风控防刷规则变更频繁：
- 宿主是一个庞大的 Spring Boot Web 单例应用；
- 风控引擎需要作为独立动态模块随时在线升级，修复漏刷漏洞；
- 宿主 `@RestController` 或业务 `@Service` 希望直接 `@Autowired` 风控接口，无需改造为 Knotra 专用 API。

## 完整代码实现

### 1. 共享风控契约

```java
package io.knotra.demo.spring;

public record RiskContext(String userId, String ip, String action) {}
public record RiskResult(boolean pass, String reason) {}

public interface RiskControlEngine {
    RiskResult evaluate(RiskContext context);
}
```

### 2. 宿主 Spring Boot 配置与动态桥

在宿主应用的 `@Configuration` 中声明 `SpringDynamicBridge`：

```java
package io.knotra.demo.spring;

import io.knotra.CapabilityKey;
import io.knotra.KnotraRuntime;
import io.knotra.spring.SpringDynamicBridge;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HostRiskSpringConfig {

    @Bean(destroyMethod = "close")
    public SpringDynamicBridge<RiskControlEngine> riskBridge(KnotraRuntime runtime) {
        return SpringDynamicBridge.mount(
                runtime,
                "risk-bridge",
                CapabilityKey.of(RiskControlEngine.class),
                CapabilityKey.of("host.risk-control", RiskControlEngine.class));
    }

    @Bean
    public RiskControlEngine riskControlEngine(SpringDynamicBridge<RiskControlEngine> bridge) {
        // 返回一个对 Spring 容器完全透明的标准单例代理 Bean
        return bridge.proxy();
    }
}
```

### 3. 宿主 Controller 业务无感调用

```java
package io.knotra.demo.spring;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private RiskControlEngine riskControlEngine; // 直接注入普通 Spring 接口

    @PostMapping("/submit")
    public String submitOrder(@RequestParam String userId, @RequestParam String ip) {
        RiskResult result = riskControlEngine.evaluate(new RiskContext(userId, ip, "CREATE_ORDER"));
        if (!result.pass()) {
            return "下单失败: " + result.reason();
        }
        return "下单成功";
    }
}
```

### 4. 动态风控规则热升级实战

```java
public class RiskPluginReloadDemo {

    public static void main(String[] args) {
        Duration timeout = Duration.ofSeconds(10);

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            // 1. 发布 v1 规则：放行所有请求
            Publication<RiskControlEngine> riskSlot = runtime.publish(
                    RiskControlEngine.class,
                    context -> new RiskResult(true, "v1: 默认放行")
            ).publication();

            // 2. 模拟宿主获取 Spring 代理
            SpringDynamicBridge<RiskControlEngine> bridge = SpringDynamicBridge.mount(
                    runtime,
                    "risk-bridge",
                    CapabilityKey.of(RiskControlEngine.class),
                    CapabilityKey.of("host.risk-control", RiskControlEngine.class)
            );
            RiskControlEngine proxy = bridge.proxy();

            // 宿主首次调用
            System.out.println(proxy.evaluate(new RiskContext("U1", "192.168.1.100", "LOGIN")));
            // 输出: RiskResult[pass=true, reason=v1: 默认放行]

            // 3. 线上发现黑产 IP，秒级热更发布 v2 黑名单规则
            System.out.println("--- 热升级风控规则至 v2 ---");
            riskSlot.update(context -> {
                if ("192.168.1.100".equals(context.ip())) {
                    return new RiskResult(false, "v2: 命中恶意 IP 黑名单");
                }
                return new RiskResult(true, "v2: 放行");
            }).awaitSettled(timeout);

            // 宿主再次调用，代理自动生效最新规则，宿主 Spring 容器零重启
            System.out.println(proxy.evaluate(new RiskContext("U1", "192.168.1.100", "LOGIN")));
            // 输出: RiskResult[pass=false, reason=v2: 命中恶意 IP 黑名单]
        }
    }
}
```

---

# 案例五：事件驱动订单流水线

## 业务背景与痛点

电商交易下单完成后，需要执行一系列后置动作：
- **审计记录**：必须串行或并行执行；
- **流水线数据加工（Waterfall）**：流水线上一节点的产出物作为下一节点的入参（如积分计算后计算赠券）；
- **责任链短路熔断（Bail）**：任何一个风控检测项拦截后立即中断后续执行；
- **动态注销与排空**：某些临时活动监听器到期后，需要优雅摘除并等待在途事件分发完成。

## 完整代码实现

```java
package io.knotra.demo.events;

import io.knotra.*;
import io.knotra.events.*;
import java.time.Duration;

public class OrderEventPipelineDemo {

    public record OrderPaidEvent(String orderId, int amount, String userId) {}

    public static void main(String[] args) {
        Duration timeout = Duration.ofSeconds(10);

        try (KnotraRuntime runtime = KnotraRuntime.create()) {
            // 1. 挂载事件总线
            MountHandle busHandle = runtime.mount("event-bus", new EventBusFactory());
            busHandle.requireActive(timeout);

            EventBus eventBus = runtime.root().view().require(EventCapabilities.EVENT_BUS);

            // 2. 声明 Parallel 并发广播定义
            EventDefinition.Parallel<OrderPaidEvent> parallelDef =
                    EventDefinition.parallel(OrderPaidEvent.class);

            // 订阅者 A：审计入库
            eventBus.subscribe(parallelDef, event ->
                    System.out.println("[Audit Log] 记录支付流水: " + event.orderId()));

            // 订阅者 B：发送短信通知
            EventSubscription smsSubscription = eventBus.subscribe(parallelDef, event ->
                    System.out.println("[SMS Notice] 发送短信至用户: " + event.userId()));

            // 3. 分发事件
            System.out.println("--- 第一次并发分发 ---");
            eventBus.dispatch(parallelDef, new OrderPaidEvent("ORD-9001", 300, "USER-A"));

            // 4. 动态摘除短信订阅并等待在途排空
            System.out.println("--- 摘除短信通知监听器 ---");
            smsSubscription.close();

            // 5. 再次分发：仅审计生效
            System.out.println("--- 第二次并发分发 ---");
            eventBus.dispatch(parallelDef, new OrderPaidEvent("ORD-9002", 500, "USER-B"));
        }
    }
}
```

---

## 最佳实践与避坑清单

1. **选择正确的依赖注入模式**：
   - 绝大多数无状态业务策略选择 `Beans.dynamic(...)` 或 `Beans.dynamicCapability(...)`；
   - 仅当组件内部强依赖某特定代际的生命周期实例（如连接池、长连接工作线程）时选择 `Beans.fixed(...)`。
2. **避免在事务回调内执行耗时 I/O**：
   - `runtime.advanced().transact(...)` 只应记录结构意图（`provide`, `revoke`, `childContext`），不可调用外部 HTTP 或阻塞查询。
3. **始终遵循有界等待原则**：
   - 生产环境中任何 `awaitSettled`、`requireActive` 或 `closeAsync` 均须显式指定 `Duration` 超时预算。
