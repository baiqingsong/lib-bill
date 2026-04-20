# LibBanknoteLCT

LCT 纸钞机 Android 串口通信引用模块，基于 [libSerial](https://github.com/baiqingsong/libSerial) 封装，提供纸钞机的连接、收款、停止、错误处理等功能。

## 功能特性

- 通过串口与 LCT 纸钞机通信（HEX 模式）
- 自动握手与连接状态管理
- **握手超时检测**（默认5秒，超时自动通知失败）
- **心跳保活机制**（默认30秒间隔，检测连接断开）
- **自动重连**（连接丢失后自动重试，最大3次）
- 支持开始收款、停止收款、接收纸币
- 完整的错误码解析与回调（含串口通信错误）
- 串口通信在后台线程执行，回调在主线程
- 线程安全的单例模式，适合多模块引用
- 支持断开、重连操作
- 轻量级设计，无 Service / BroadcastReceiver，不影响其他支付模块

## 引入方式

### JitPack 远程依赖

**Step 1.** 在项目根目录的 `settings.gradle` 或 `build.gradle` 中添加 JitPack 仓库：

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
```

**Step 2.** 在 app 模块的 `build.gradle` 中添加依赖：

```groovy
dependencies {
    implementation 'com.github.baiqingsong:lib-bill:Tag'
}
```

> 将 `Tag` 替换为最新版本号，如 `1.0.2`。

### 本地模块依赖

在项目 `settings.gradle` 中添加：

```groovy
include ':banknote_lct'
```

在 app 模块的 `build.gradle` 中添加依赖：

```groovy
dependencies {
    implementation project(path: ':banknote_lct')
}
```

## 类说明

### BanknoteManager

纸钞机管理类（核心类），单例模式。负责串口的打开、关闭、数据收发和协议解析。

#### 状态枚举 `BanknoteManager.State`

| 状态 | 说明 |
|------|------|
| `IDLE` | 未初始化 / 已断开 |
| `CONNECTING` | 串口已打开，等待设备握手 |
| `CONNECTED` | 握手成功，设备就绪 |
| `RECEIVING` | 正在收款中 |

状态流转：`IDLE` → `CONNECTING` → `CONNECTED` ⇄ `RECEIVING`

#### 公开方法

| 方法 | 说明 |
|------|------|
| `getInstance(Context)` | 获取单例实例（内部使用 ApplicationContext，不会泄漏 Activity） |
| `setListener(BanknoteReceiverListener)` | 设置纸钞机事件回调监听器 |
| `removeListener()` | 移除事件监听器（建议在 Activity/Fragment 销毁时调用） |
| `getState()` | 获取当前纸钞机运行状态 |
| `isConnected()` | 查询是否已连接（状态为 CONNECTED 或 RECEIVING） |
| `startPort(int port)` | 打开指定串口号连接纸钞机 |
| `reconnect()` | 重新连接（先断开再重新打开，使用上次的串口号） |
| `disconnect()` | 断开串口连接（不销毁实例，可再次 startPort） |
| `startMoney(int money)` | 开始收款，money 为目标收款金额（必须 > 0） |
| `stopMoney()` | 停止收款 |
| `destroy()` | 释放所有资源并销毁单例，之后需重新 getInstance |

---

### BanknoteReceiverListener

纸钞机事件回调接口，所有回调均在**主线程**执行。

| 回调方法 | 说明 |
|----------|------|
| `onConnected(boolean connected)` | 连接状态回调。`true` 握手成功，`false` 连接失败 |
| `onStartMoney(boolean success)` | 设备确认进入收款状态 |
| `onStopMoney(boolean success)` | 设备确认停止收款 |
| `onMoneyReceived(int moneyIndex, int totalMoney)` | 收到纸币。`moneyIndex` 为面额通道编号（1-16），`totalMoney` 为目标金额 |
| `onError(String errorMsg)` | 设备错误回调，errorMsg 为中文错误描述 |

---

### BanknoteCommand

LCT 纸钞机通信协议指令与常量定义（工具类，不可实例化）。

#### 响应码常量

| 常量 | 值 | 说明 |
|------|----|------|
| `RESPONSE_HANDSHAKE` | `808f` | 握手响应 |
| `RESPONSE_DEVICE_STATUS` | `10` | 设备空闲状态 |
| `RESPONSE_START_MONEY` | `3e` | 开始收款确认 |
| `RESPONSE_STOP_MONEY` | `5e` | 停止收款确认 |

#### 数据范围常量

| 常量 | 值 | 说明 |
|------|----|------|
| `ERROR_RANGE_START` | `0x20` | 错误码起始值 |
| `ERROR_RANGE_END` | `0x2F` | 错误码结束值 |
| `MONEY_RANGE_START` | `0x40` | 收款码起始值 |
| `MONEY_RANGE_END` | `0x4F` | 收款码结束值 |

#### 错误码对照表

| 错误码 | 描述 |
|--------|------|
| `0x20` | 马达故障 |
| `0x21` | 检验码故障 |
| `0x22` | 卡币 |
| `0x23` | 纸币移开 |
| `0x24` | 纸箱移开 |
| `0x25` | 电眼故障 |
| `0x27` | 钓鱼 |
| `0x28` | 纸箱故障 |
| `0x29` | 拒收 |
| `0x2F` | 异常情况结束 |

#### 指令方法

| 方法 | 返回值 | 说明 |
|------|--------|------|
| `getStatusCommand()` | `"02"` | 状态查询 / 握手回应指令 |
| `getReceiverCommand()` | `"02"` | 接受纸钞确认指令 |
| `getRejectCommand()` | `"0f"` | 拒收纸钞指令 |
| `getStartMoneyCommand()` | `"3e"` | 开启收款指令 |
| `getStopMoneyCommand()` | `"5e"` | 停止收款指令 |

## 使用示例

### 1. 初始化并设置监听

```java
BanknoteManager manager = BanknoteManager.getInstance(context);
manager.setListener(new BanknoteReceiverListener() {
    @Override
    public void onConnected(boolean connected) {
        if (connected) {
            Log.d("Banknote", "纸钞机连接成功");
        } else {
            Log.e("Banknote", "纸钞机连接失败");
        }
    }

    @Override
    public void onStartMoney(boolean success) {
        Log.d("Banknote", "开始收款: " + success);
    }

    @Override
    public void onStopMoney(boolean success) {
        Log.d("Banknote", "停止收款: " + success);
    }

    @Override
    public void onMoneyReceived(int moneyIndex, int totalMoney) {
        Log.d("Banknote", "收到纸币，面额通道: " + moneyIndex + ", 目标金额: " + totalMoney);
    }

    @Override
    public void onError(String errorMsg) {
        Log.e("Banknote", "错误: " + errorMsg);
    }
});
```

### 2. 打开串口

```java
// 打开串口号 4
manager.startPort(4);
```

### 3. 开始收款

```java
// 设置目标收款金额 100 并开启收款
manager.startMoney(100);
```

### 4. 停止收款

```java
manager.stopMoney();
```

### 5. 重连

```java
// 断开后重新连接（使用上次的串口号）
manager.reconnect();
```

### 6. 仅断开连接（保留实例）

```java
manager.disconnect();
// 之后可以再次调用 startPort 重新连接
manager.startPort(4);
```

### 7. 销毁释放资源

```java
// 在 Activity/Fragment 的 onDestroy 中调用
manager.removeListener();
manager.destroy();
```

### 完整 Activity 示例

```java
public class PayActivity extends AppCompatActivity {

    private BanknoteManager mBanknoteManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pay);

        mBanknoteManager = BanknoteManager.getInstance(this);
        mBanknoteManager.setListener(new BanknoteReceiverListener() {
            @Override
            public void onConnected(boolean connected) {
                // 连接成功后可以开始收款
            }

            @Override
            public void onStartMoney(boolean success) {
                // 设备确认收款已开启
            }

            @Override
            public void onStopMoney(boolean success) {
                // 设备确认收款已停止
            }

            @Override
            public void onMoneyReceived(int moneyIndex, int totalMoney) {
                // 处理收到的纸币
            }

            @Override
            public void onError(String errorMsg) {
                // 处理设备错误
            }
        });

        // 打开串口
        mBanknoteManager.startPort(4);
    }

    public void onStartPayClick(View view) {
        mBanknoteManager.startMoney(100);
    }

    public void onStopPayClick(View view) {
        mBanknoteManager.stopMoney();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mBanknoteManager != null) {
            mBanknoteManager.removeListener();
            mBanknoteManager.destroy();
        }
    }
}
```

## 串口通信参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 波特率 | 9600 | LCT 纸钞机标准波特率 |
| 数据位 | 8 | — |
| 停止位 | 1 | — |
| 校验位 | E（偶校验） | — |
| 通信模式 | HEX | 十六进制模式 |

## 依赖

- [libSerial 1.0.9](https://github.com/baiqingsong/libSerial) — Android 串口通信工具库
- compileSdk 34
- minSdk 28
- targetSdk 34
