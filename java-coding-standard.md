# Java 後端開發 Coding Standard

> 本專案使用 Java 17+ / Spring Boot。
> **每次撰寫任何 Java 程式碼前，必須先完成以下核查流程，再輸出程式碼。**

---

## ⚡ 撰寫前強制核查（每個功能都要過）

在輸出程式碼前，先依序自問：

1. **SOLID - SRP**：這個類別只有一個改變的理由嗎？
2. **SOLID - OCP**：新功能可以透過擴充解決，不需修改現有程式碼嗎？
3. **SOLID - LSP**：子類別能完整替換父類別嗎？
4. **SOLID - ISP**：介面是否只包含使用方真正需要的方法？
5. **SOLID - DIP**：高層模組依賴抽象（介面）而非具體實作嗎？
6. **DRY**：這段邏輯在系統裡已經存在了嗎？可以抽取共用嗎？
7. **KISS**：有沒有更簡單的方式達到同樣效果？
8. **YAGNI**：這個功能是「現在」確定需要的，而非「未來可能需要」？
9. **充血模型**：業務邏輯是否應該放進 Domain 物件，而不是 Service 層？
10. **TDD - 新功能**：是否先寫失敗的測試，再寫實作？（Red → Green → Refactor）
11. **TDD - Refactor**：重構前測試是否全部綠燈？重構後是否再跑一次確認沒有破壞？
12. **型別宣告**：是否明確宣告型別？禁止使用 `var` 接收任何回傳值。
13. **Null / 空值檢查**：是否使用 Spring 或 Apache Commons 工具進行檢查？禁止直接用 `== null` 或 `.isEmpty()`。
14. **完整類別名稱**：程式碼中是否出現 `com.xxx.Foo` 這類帶 package 的寫法？應改用 `import` + 短名稱。
15. **資料類別選型**：新增資料類別時，是否依序考慮 `record` → Lombok → 自行撰寫？

---

## 1. SOLID 原則

### SRP — 單一職責
```java
// ❌ 一個 Service 做太多事
public class OrderService {
    public void createOrder(Order order) { ... }
    public void sendEmail(Order order) { ... }      // 不該在這
    public byte[] generateReport(List<Order> o) { ... } // 不該在這
}

// ✅ 各司其職
public class OrderService { ... }
public class OrderNotificationService { ... }
public class OrderReportService { ... }
```

### OCP — 開放封閉
```java
// ❌ 每次新需求都要改這個 if/else
public BigDecimal calcDiscount(Order o, String type) {
    if (type.equals("VIP")) return o.total().multiply(new BigDecimal("0.9"));
    if (type.equals("MEMBER")) return o.total().multiply(new BigDecimal("0.95"));
    return o.total();
}

// ✅ 策略模式，新折扣 = 新類別，不改舊程式碼
public interface DiscountStrategy {
    BigDecimal apply(Order order);
}
@Component("VIP")
public class VipDiscountStrategy implements DiscountStrategy { ... }
```

### DIP — 依賴反轉
```java
// ✅ 建構子注入介面
@Service
public class OrderService {
    private final OrderRepository orderRepository;     // 介面
    private final PaymentGateway paymentGateway;       // 介面

    public OrderService(OrderRepository orderRepository,
                        PaymentGateway paymentGateway) {
        this.orderRepository = orderRepository;
        this.paymentGateway = paymentGateway;
    }
}
```

---

## 2. DRY — 不重複自己

相同邏輯出現 2 次以上就抽取。優先抽成 Value Object 或共用方法。

```java
// ❌ 重複的驗證邏輯散落各處
if (email == null || !email.matches("^[\\w-.]+@[\\w-]+\\.[a-z]{2,}$"))
    throw new InvalidEmailException();

// ✅ Value Object 封裝，一處定義，到處使用
public final class Email {
    private static final String PATTERN = "^[\\w-.]+@[\\w-]+\\.[a-z]{2,}$";
    public Email(String value) {
        if (value == null || !value.matches(PATTERN))
            throw new InvalidEmailException(value);
        this.value = value;
    }
}
```

---

## 3. KISS — 保持簡單

最簡單能解決問題的方案就是最好的方案。

```java
// ❌ 過度設計
public class UserStatusEvaluator {
    private final StatusRuleEngine engine;
    public UserStatus evaluate(User user) {
        return engine.process(rules, user.toContext());
    }
}

// ✅ 直接解決問題
public UserStatus getStatus(User user) {
    if (user.isBlocked()) return UserStatus.BLOCKED;
    if (user.hasActiveSubscription()) return UserStatus.ACTIVE;
    return UserStatus.INACTIVE;
}
```

判斷標準：能用 `if/else` 解決就不要用 Pattern；能用一個方法解決就不建新類別。

---

## 4. YAGNI — 只做現在需要的

不為「未來可能的需求」預先設計抽象層。

```java
// ❌ 需求只要存資料，卻先建了沒用到的 Exporter 體系
public interface UserExporter<T> { ... }
public class CsvExporter implements UserExporter<String> { ... }
public class XmlExporter implements UserExporter<String> { ... }  // 沒人要求

// ✅ 只做現在需要的
public String exportToCsv(User user) {
    return user.getName() + "," + user.getEmail();
}
```

---

## 5. 充血模型（Rich Domain Model）⭐

**最重要的原則：業務邏輯屬於 Domain 物件，不屬於 Service 層。**

### ❌ 貧血模型（反模式）
```java
// Domain 物件只是資料容器
public class Order {
    private OrderStatus status;
    // 只有 getter/setter
}

// Service 層承擔所有業務邏輯 → Service 越來越肥大
public class OrderService {
    public void cancelOrder(Long id) {
        Order order = orderRepository.findById(id).orElseThrow();
        if (order.getStatus() == OrderStatus.SHIPPED)    // 業務規則在 Service
            throw new IllegalStateException("...");
        order.setStatus(OrderStatus.CANCELLED);           // 直接操作狀態
        orderRepository.save(order);
    }
}
```

### ✅ 充血模型（正確做法）
```java
public class Order {
    private OrderStatus status;
    private List<OrderItem> items;

    // 工廠方法，包含創建時的業務驗證
    public static Order create(CustomerId customerId, List<OrderItem> items) {
        if (items == null || items.isEmpty())
            throw new InvalidOrderException("Order must have at least one item");
        Order order = new Order();
        order.status = OrderStatus.PENDING;
        order.items = new ArrayList<>(items);
        return order;
    }

    // 業務行為封裝在物件內
    public void cancel() {
        if (this.status == OrderStatus.SHIPPED)
            throw new OrderCancellationException("Cannot cancel shipped order");
        if (this.status == OrderStatus.CANCELLED)
            throw new OrderCancellationException("Order already cancelled");
        this.status = OrderStatus.CANCELLED;
    }

    public void addItem(OrderItem item) {
        if (this.status != OrderStatus.PENDING)
            throw new InvalidOrderOperationException("Cannot modify non-pending order");
        this.items.add(item);
    }

    public boolean isCancellable() {
        return status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED;
    }
}

// Service 層只負責協調：取資料、呼叫 Domain 行為、存檔、發事件
@Service
public class OrderService {
    public void cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.cancel();  // 業務邏輯委派給 Domain 物件
        orderRepository.save(order);
        eventPublisher.publish(new OrderCancelledEvent(order));
    }
}
```

### Value Object — 封裝基本型別的業務規則
```java
public final class Money {
    private final BigDecimal amount;
    private final Currency currency;

    public Money(BigDecimal amount, Currency currency) {
        if (amount.compareTo(BigDecimal.ZERO) < 0)
            throw new InvalidMoneyException("Amount cannot be negative");
        this.amount = amount.setScale(2, RoundingMode.HALF_UP);
        this.currency = Objects.requireNonNull(currency);
    }

    public Money add(Money other) {
        if (!this.currency.equals(other.currency))
            throw new CurrencyMismatchException();
        return new Money(this.amount.add(other.amount), this.currency);
    }

    @Override public boolean equals(Object o) { ... }
    @Override public int hashCode() { ... }
}
```

### 邏輯放置對照表

| 程式碼種類 | 放置位置 |
|----------|---------|
| 業務規則驗證 | Domain 物件（Entity / Value Object） |
| 跨多個 Domain 物件的業務邏輯 | Domain Service |
| 流程協調（呼叫 Repository、發事件） | Application Service |
| HTTP 轉換 | Controller + DTO |
| 資料庫存取 | Repository 實作 |
| 外部服務整合 | Infrastructure 層 |

---

## 6. 命名規範

```java
// 類別：名詞，清楚表達職責
OrderRepository, PaymentGateway, CustomerService

// 方法：動詞，表達意圖而非實作
findById()      // 不用 getById()
cancelOrder()   // 不用 setStatusToCancelled()

// 布林：is / has / can 開頭
boolean isActive()
boolean hasUnpaidInvoices()
boolean canBeShipped()

// 常數
static final int MAX_RETRY_ATTEMPTS = 3;
```

---

## 7. 例外處理

```java
// 使用有意義的自訂例外，分層管理
public class OrderCancellationException extends DomainException { ... }
public class OrderNotFoundException extends ApplicationException { ... }

// 禁止吞掉例外
// ❌ catch (Exception e) { /* 什麼都不做 */ }

// ✅
catch (OrderCancellationException e) {
    log.warn("Order cancellation failed: {}", e.getMessage());
    throw new OrderProcessingException("Unable to cancel order", e);
}
```

---

## 8. TDD（Test-Driven Development）⭐

**強制規範：開發新功能與每次 Refactor 都必須遵循 TDD 流程。不允許先寫實作再補測試。**

---

### 8.1 TDD 三步循環（Red → Green → Refactor）

```
🔴 Red    → 先寫一個會失敗的測試，確認測試本身是正確的
🟢 Green  → 寫最少量的實作程式碼，讓測試通過（不求完美）
🔵 Refactor → 在測試全綠的保護下，整理程式碼品質
```

**每個循環只處理一個小需求，不要一次跳太遠。**

---

### 8.2 新功能開發流程

```
1. 確認需求 → 拆解成最小可測試的行為單元
2. 🔴 寫失敗測試（測試先存在，此時編譯或執行會失敗）
3. 🟢 寫最小實作讓測試通過（允許暫時寫醜一點）
4. 🔵 Refactor（見 8.3）
5. 重複以上步驟直到功能完整
```

```java
// Step 1：需求「訂單建立時，若無商品應拋出例外」

// Step 2：🔴 先寫測試（此時 Order.create 還不存在）
@Test
void should_throw_when_creating_order_without_items() {
    assertThatThrownBy(() -> Order.create(customerId, List.of()))
        .isInstanceOf(InvalidOrderException.class)
        .hasMessageContaining("at least one item");
}

// Step 3：🟢 寫最小實作讓測試通過
public static Order create(CustomerId customerId, List<OrderItem> items) {
    if (items == null || items.isEmpty())
        throw new InvalidOrderException("Order must have at least one item");
    // ... 最小可運作的實作
}

// Step 4：🔵 測試綠燈後再 Refactor（見 8.3）
```

---

### 8.3 Refactor 流程（強制）

**每次 Refactor 前後都必須執行測試，確保行為未改變。**

```
Refactor 標準流程：
1. 確認目前所有測試全部綠燈 ✅
2. 進行一個小的重構動作（只做一件事）
3. 立即執行測試，確認仍然全部綠燈 ✅
4. 重複 2-3 直到完成
```

```
❌ 禁止：一次進行多個重構，然後才跑測試
✅ 正確：每做一個小改動就跑一次測試
```

Refactor 的常見時機（對照 SOLID / DRY / KISS）：

| 觀察到的問題 | Refactor 動作 |
|------------|--------------|
| 方法超過 20 行 | 抽取 private method |
| 相同邏輯出現 2 次 | 抽取共用方法（DRY） |
| 巢狀 if 超過 2 層 | Early return 或 策略模式（KISS） |
| Service 包含業務邏輯 | 移入 Domain 物件（充血模型） |
| 類別職責不清 | 拆分類別（SRP） |

---

### 8.4 測試分層規範

```
單元測試（Unit Test）      → 測試 Domain 物件、Value Object、純邏輯
整合測試（Integration Test）→ 測試 Repository、外部服務整合
端對端測試（E2E Test）     → 測試完整 API 流程
```

**充血模型的最大優點：Domain 物件不依賴 Spring，單元測試速度快、不需啟動容器。**

```java
// ✅ Domain 物件單元測試 - 不需要 @SpringBootTest
class OrderTest {

    @Test
    void should_cancel_pending_order() {
        Order order = Order.create(customerId, List.of(item));
        order.cancel();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void should_throw_when_cancelling_shipped_order() {
        Order order = Order.create(customerId, List.of(item));
        order.confirm();
        order.ship();

        assertThatThrownBy(() -> order.cancel())
            .isInstanceOf(OrderCancellationException.class)
            .hasMessageContaining("Cannot cancel shipped order");
    }

    @Test
    void should_recalculate_total_when_item_added() {
        Order order = Order.create(customerId, List.of(itemA));
        order.addItem(itemB);

        assertThat(order.total()).isEqualTo(itemA.subtotal().add(itemB.subtotal()));
    }
}
```

```java
// ✅ Application Service 整合測試 - Mock 外部依賴
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock OrderRepository orderRepository;
    @Mock ApplicationEventPublisher eventPublisher;
    @InjectMocks OrderService orderService;

    @Test
    void should_publish_event_after_cancellation() {
        Order order = Order.create(customerId, List.of(item));
        when(orderRepository.findById(1L)).thenReturn(Optional.of(order));

        orderService.cancelOrder(1L);

        verify(orderRepository).save(order);
        verify(eventPublisher).publishEvent(any(OrderCancelledEvent.class));
    }
}
```

---

### 8.5 測試命名規範

測試方法名稱必須清楚描述「情境」與「預期結果」，使用 `should_[預期結果]_when_[情境]` 格式：

```java
// ✅ 清楚的測試名稱
void should_throw_InvalidOrderException_when_items_are_empty()
void should_return_cancelled_status_when_order_is_pending()
void should_recalculate_total_when_item_is_removed()

// ❌ 無意義的測試名稱
void testCancel()
void test1()
void cancelOrderTest()
```

---

### 8.6 測試覆蓋要求

每個 Domain 物件的公開方法都必須有對應測試，且需涵蓋：

| 測試類型 | 說明 |
|---------|------|
| 正常路徑（Happy Path） | 功能正確執行的情境 |
| 邊界條件（Edge Case） | 空值、最大值、最小值 |
| 例外路徑（Exception Path） | 各種業務規則違反的情境 |

```java
// 一個 cancel() 方法需要的完整測試覆蓋
void should_cancel_successfully_when_status_is_pending()      // Happy Path
void should_cancel_successfully_when_status_is_confirmed()    // Happy Path
void should_throw_when_status_is_shipped()                    // Exception Path
void should_throw_when_status_is_already_cancelled()          // Exception Path
```

---

## 9. 型別宣告規範

**禁止使用 `var`，所有變數必須明確宣告型別。**

`var` 雖然是 Java 10+ 合法語法，但會降低程式碼可讀性，且與充血模型的明確性原則衝突。

```java
// ❌ 禁止使用 var - 看不出型別，增加閱讀負擔
var order = orderRepository.findById(id).orElseThrow();
var items = orderService.getItems(orderId);
var result = paymentGateway.charge(amount);

// ✅ 明確宣告型別 - 意圖清晰，IDE 與 Code Review 友善
Order order = orderRepository.findById(id).orElseThrow();
List<OrderItem> items = orderService.getItems(orderId);
PaymentResult result = paymentGateway.charge(amount);
```

**適用範圍：所有情境，包含 for-each、try-with-resources、Stream 中間變數，一律不得使用 `var`。**

```java
// ❌ 禁止
for (var item : order.getItems()) { ... }

// ✅ 正確
for (OrderItem item : order.getItems()) { ... }
```

---

## 10. Null / 空值檢查規範

**統一使用 Spring Framework 工具類別進行 null 與空值檢查，禁止直接用 `== null` 或 `.isEmpty()`。**

專案已依賴 Spring，不需額外引入其他套件，直接使用 `org.springframework.util` 下的工具。

### 10.1 統一使用的工具類別

| 情境 | 使用工具 | 禁止寫法 |
|------|---------|---------|
| 物件是否為 null | `Objects.requireNonNull()` | `obj == null` |
| String 是否為空 | `StringUtils.hasText()` | `str == null \|\| str.isEmpty()` |
| Collection 是否為空 | `CollectionUtils.isEmpty()` | `list == null \|\| list.isEmpty()` |
| 斷言參數不為 null | `Assert.notNull()` | 手寫 if + throw |
| 斷言字串有內容 | `Assert.hasText()` | 手寫 if + throw |

### 10.2 String 檢查

```java
import org.springframework.util.StringUtils;

// ❌ 禁止 - 手動組合判斷，容易遺漏
if (name == null || name.isEmpty()) { ... }
if (name == null || name.trim().isEmpty()) { ... }

// ✅ 正確 - hasText() 同時檢查 null、空字串、純空白字串
if (!StringUtils.hasText(name)) {
    throw new InvalidArgumentException("Name must not be blank");
}

// ✅ 正確 - hasLength() 只檢查 null 與空字串（不排除空白）
if (!StringUtils.hasLength(code)) {
    throw new InvalidArgumentException("Code must not be empty");
}
```

### 10.3 Collection 檢查

```java
import org.springframework.util.CollectionUtils;

// ❌ 禁止
if (items == null || items.isEmpty()) { ... }

// ✅ 正確 - CollectionUtils.isEmpty() 同時處理 null 與空集合
if (CollectionUtils.isEmpty(items)) {
    throw new InvalidOrderException("Order must have at least one item");
}
```

### 10.4 物件 Null 檢查（防禦性驗證）

```java
import java.util.Objects;
import org.springframework.util.Assert;

// ❌ 禁止 - 手寫 null guard
if (customerId == null) throw new IllegalArgumentException("customerId must not be null");

// ✅ 方式一：Objects.requireNonNull（回傳值可繼續使用）
CustomerId id = Objects.requireNonNull(customerId, "customerId must not be null");

// ✅ 方式二：Spring Assert（語意更清楚，適合 Domain / Application 層入口）
Assert.notNull(customerId, "customerId must not be null");
Assert.hasText(name, "name must not be blank");
Assert.notEmpty(items, "items must not be empty");
```

### 10.5 Value Object 內的綜合應用

```java
public final class Email {
    private final String value;

    public Email(String value) {
        // ✅ 使用 Spring Assert 取代手寫 null + isEmpty 判斷
        Assert.hasText(value, "Email must not be blank");
        Assert.isTrue(value.matches(PATTERN), "Invalid email format: " + value);
        this.value = value;
    }
}

public static Order create(CustomerId customerId, List<OrderItem> items) {
    // ✅ 使用工具類別，語意清晰
    Assert.notNull(customerId, "customerId must not be null");
    Assert.notEmpty(items, "Order must have at least one item");

    Order order = new Order();
    order.status = OrderStatus.PENDING;
    order.items = new ArrayList<>(items);
    return order;
}
```

---

## 11. 完整類別名稱規範

**禁止在程式碼中直接使用帶 package 的完整類別名稱；一律透過 `import` 引入後使用短名稱。**

```java
// ❌ 禁止 - package name 散落在程式碼裡，難以閱讀
public class OrderService {
    private final com.java.system.agent.analysis.port.SourceCodePort sourceCodePort;

    public void process(com.java.system.agent.analysis.model.CallGraph graph) { ... }
}

// ✅ 正確 - import 統一管理，程式碼保持簡潔
import com.java.system.agent.analysis.port.SourceCodePort;
import com.java.system.agent.analysis.model.CallGraph;

public class OrderService {
    private final SourceCodePort sourceCodePort;

    public void process(CallGraph graph) { ... }
}
```

唯一例外：同名類別衝突時，次要的那個保留完整名稱。

```java
import java.util.Date;

// 次要的保留完整名稱，避免 alias
java.sql.Date sqlDate = ...;
```

---

## 12. 資料類別選型規範

新增資料類別（DTO、VO、Context、Request、Response）時，依序優先考慮：

**1. `record`（首選）** — 不可變、自動產生 equals / hashCode / toString，零樣板

```java
// ✅ 適合：不可變的 DTO、Value Object、查詢結果
public record CallGraphResult(String entryPoint, List<String> callChain) {}
```

**2. Lombok（次選）** — 可變物件、需要 Builder 或繼承時使用

```java
// ✅ 適合：需要 Builder 模式、可變狀態
@Data
@Builder
public class SlackMessageContext {
    private String channelId;
    private String threadTs;
    private String userId;
}
```

**3. 自行撰寫（最後手段）** — 只有當 `record` 與 Lombok 都無法滿足業務行為時

```java
// ✅ 適合：需要封裝業務邏輯的充血 Domain 物件
public final class Money {
    private final BigDecimal amount;
    private final Currency currency;

    public Money add(Money other) { ... }  // 業務行為
}
```

| 情境 | 選型 |
| --- | --- |
| 不可變 DTO / VO / 查詢結果 | `record` |
| 需要 Builder / 可變欄位 | Lombok `@Data` / `@Builder` |
| 含業務邏輯的 Domain 物件 | 自行撰寫 |
