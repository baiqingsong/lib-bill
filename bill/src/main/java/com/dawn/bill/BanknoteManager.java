package com.dawn.bill;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

/**
 * 纸钞机管理类（串口无关版本）
 * <p>
 * 负责 LCT 纸钞机通信协议的状态机管理。
 * 串口的打开/关闭/读写由宿主 App 负责，通过 {@link BanknoteSerialPort} 接口注入。
 * </p>
 * <p>
 * 主要解决的问题：
 * <ul>
 *   <li>LSerialUtil 的 20ms 合并缓冲导致多条协议消息粘连，现改为逐条解析</li>
 *   <li>收款状态（RECEIVING）期间停止心跳，防止 "02" 指令干扰纸钞识别/接受</li>
 *   <li>握手超时检测</li>
 *   <li>与串口库解耦，适用于任何串口实现</li>
 * </ul>
 * </p>
 * <p>
 * 宿主 App 集成示例（在 PaymentService 中）：
 * <pre>
 *   BanknoteManager manager = BanknoteManager.getInstance(context);
 *   manager.setListener(listener);
 *
 *   // 打开串口，将收发桥接到 manager
 *   LSerialUtil serial = new LSerialUtil(port, 9600, 8, 1, 'E', TYPE_HEX, new OnSerialListener() {
 *       public void startError()            { manager.onSerialError("串口打开失败"); }
 *       public void receiverError()         { manager.onSerialError("串口接收错误"); }
 *       public void sendError()             {}
 *       public void getReceiverStr(String s){ manager.onReceived(s); }
 *   });
 *   manager.setSerialPort(hex -> serial.sendHexMsg(hex));
 *   manager.startPort(port);
 *
 *   // 开始收款
 *   manager.startMoney(2990);
 *   // 停止收款
 *   manager.stopMoney();
 *   // 销毁
 *   manager.destroy();
 * </pre>
 * </p>
 */
public class BanknoteManager {

    private static final String TAG = "BanknoteManager";

    /** 握手超时（毫秒） */
    private static final long HANDSHAKE_TIMEOUT_MS = 5000;
    /** 心跳发送间隔（毫秒） */
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    /** 心跳响应超时（毫秒） */
    private static final long HEARTBEAT_TIMEOUT_MS = 5000;

    /** 纸钞机运行状态 */
    public enum State {
        IDLE,       // 未初始化 / 已断开
        CONNECTING, // 串口已打开，等待握手
        CONNECTED,  // 握手成功，空闲
        RECEIVING   // 正在收款
    }

    /**
     * 串口发送接口（由宿主 App 提供实现）
     */
    public interface BanknoteSerialPort {
        /** 向纸钞机发送十六进制指令 */
        void send(String hexData);
    }

    private static volatile BanknoteManager sInstance;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Object mLock = new Object();

    private volatile BanknoteReceiverListener mListener;
    private volatile BanknoteSerialPort mSerialPort;
    private volatile State mState = State.IDLE;
    private volatile int mTotalMoney;
    private volatile boolean mDestroyed;
    private volatile boolean mHeartbeatResponseReceived;
    private int mCurrentPort = -1;

    // ==================== 心跳 Runnables ====================

    private final Runnable mHeartbeatCheckRunnable = () -> {
        if (mDestroyed || !isConnected()) return;
        if (!mHeartbeatResponseReceived) {
            Log.w(TAG, "Heartbeat timeout, connection may be lost");
            handleConnectionLost();
        } else {
            scheduleNextHeartbeat();
        }
    };

    private final Runnable mHeartbeatRunnable = () -> {
        if (mDestroyed || !isConnected()) return;
        mHeartbeatResponseReceived = false;
        sendMsg(BanknoteCommand.getStatusCommand());
        mMainHandler.postDelayed(mHeartbeatCheckRunnable, HEARTBEAT_TIMEOUT_MS);
    };

    // ==================== 握手超时 Runnable ====================

    private final Runnable mHandshakeTimeoutRunnable = () -> {
        synchronized (mLock) {
            if (mState != State.CONNECTING) return;
            mState = State.IDLE;
        }
        Log.w(TAG, "Handshake timeout");
        notifyOnMainThread(() -> {
            BanknoteReceiverListener l = mListener;
            if (l != null) {
                l.onConnected(false);
                l.onError("握手超时，连接失败");
            }
        });
    };

    // ==================== 构造 & 单例 ====================

    private BanknoteManager() {
    }

    public static BanknoteManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (BanknoteManager.class) {
                if (sInstance == null) {
                    sInstance = new BanknoteManager();
                }
            }
        }
        return sInstance;
    }

    // ==================== 公开 API ====================

    public void setListener(BanknoteReceiverListener listener) {
        mListener = listener;
    }

    public void removeListener() {
        mListener = null;
    }

    /**
     * 注入串口发送实现（由宿主 App 提供）
     */
    public void setSerialPort(BanknoteSerialPort serialPort) {
        mSerialPort = serialPort;
    }

    public State getState() {
        return mState;
    }

    public boolean isConnected() {
        State s = mState;
        return s == State.CONNECTED || s == State.RECEIVING;
    }

    public int getCurrentPort() {
        return mCurrentPort;
    }

    public int getTotalMoney() {
        return mTotalMoney;
    }

    /**
     * 通知管理器串口已打开，开始等待握手。
     * 宿主 App 必须在调用此方法前先打开串口并调用 {@link #setSerialPort}。
     *
     * @param port 串口号（仅用于记录，实际通信由 BanknoteSerialPort 处理）
     */
    public void startPort(int port) {
        if (port < 0) return;
        synchronized (mLock) {
            if (mDestroyed) return;
            if (mState != State.IDLE) {
                Log.w(TAG, "startPort called but state=" + mState + ", ignoring");
                return;
            }
            mCurrentPort = port;
            mState = State.CONNECTING;
        }
        Log.d(TAG, "startPort: waiting for handshake on port " + port);
        scheduleHandshakeTimeout();
    }

    /**
     * 宿主 App 在串口收到数据时调用此方法。
     * 支持多条协议消息粘连（LSerialUtil 20ms 合并缓冲导致）。
     *
     * @param hexData 收到的十六进制字符串（大小写均可）
     */
    public void onReceived(String hexData) {
        if (TextUtils.isEmpty(hexData) || mDestroyed) return;
        Log.e(TAG, "Received raw: [" + hexData + "] state=" + mState);
        parseMessages(hexData.toLowerCase());
    }

    /**
     * 宿主 App 在串口发生错误时调用此方法。
     */
    public void onSerialError(String errorMsg) {
        Log.e(TAG, "Serial error: " + errorMsg);
        handleConnectionLost();
        notifyOnMainThread(() -> {
            BanknoteReceiverListener l = mListener;
            if (l != null) l.onError(errorMsg);
        });
    }

    /**
     * 开始收款
     *
     * @param money 目标收款金额（必须大于 0）
     */
    public void startMoney(int money) {
        if (money <= 0) {
            Log.w(TAG, "Invalid money: " + money);
            return;
        }
        if (!isConnected()) {
            Log.w(TAG, "Not connected, cannot startMoney. state=" + mState);
            return;
        }
        mTotalMoney = money;
        sendMsg(BanknoteCommand.getStartMoneyCommand());
    }

    /**
     * 停止收款
     */
    public void stopMoney() {
        if (!isConnected()) {
            Log.w(TAG, "Not connected, cannot stopMoney. state=" + mState);
            return;
        }
        mTotalMoney = 0;
        sendMsg(BanknoteCommand.getStopMoneyCommand());
    }

    /**
     * 断开连接（不销毁实例）
     */
    public void disconnect() {
        synchronized (mLock) {
            mTotalMoney = 0;
            mState = State.IDLE;
            mCurrentPort = -1;
        }
        stopHeartbeat();
        cancelHandshakeTimeout();
    }

    /**
     * 销毁单例，释放所有资源
     */
    public void destroy() {
        synchronized (mLock) {
            mDestroyed = true;
            mState = State.IDLE;
            mTotalMoney = 0;
            mListener = null;
            mSerialPort = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        synchronized (BanknoteManager.class) {
            sInstance = null;
        }
        Log.d(TAG, "BanknoteManager destroyed");
    }

    // ==================== 内部：消息解析 ====================

    /**
     * 解析可能粘连的十六进制消息流。
     * <p>
     * LCT 协议消息格式：
     * <ul>
     *   <li>2 字节（4 hex 字符）：以 {@code 80} 或 {@code 81} 开头（如 {@code 808f}、{@code 8140}）</li>
     *   <li>1 字节（2 hex 字符）：其他指令（如 {@code 10}、{@code 3e}、{@code 40}）</li>
     * </ul>
     * </p>
     */
    private void parseMessages(String data) {
        int i = 0;
        while (i < data.length()) {
            if (i + 1 >= data.length()) break;
            String prefix = data.substring(i, i + 2);
            if ("80".equals(prefix) || "81".equals(prefix)) {
                // 2 字节消息
                if (i + 4 <= data.length()) {
                    handleSingleMessage(data.substring(i, i + 4));
                }
                i += 4;
            } else {
                // 1 字节消息
                handleSingleMessage(data.substring(i, i + 2));
                i += 2;
            }
        }
    }

    /**
     * 处理单条协议消息
     */
    private void handleSingleMessage(String msg) {
        if (TextUtils.isEmpty(msg)) return;

        switch (msg) {
            case BanknoteCommand.RESPONSE_HANDSHAKE: // "808f"
                cancelHandshakeTimeout();
                sendMsg(BanknoteCommand.getStatusCommand()); // 回复 "02"
                synchronized (mLock) {
                    if (mState == State.CONNECTING) {
                        mState = State.CONNECTED;
                    }
                }
                startHeartbeat();
                Log.i(TAG, "Handshake OK → CONNECTED");
                notifyOnMainThread(() -> {
                    BanknoteReceiverListener l = mListener;
                    if (l != null) l.onConnected(true);
                });
                return;

            case BanknoteCommand.RESPONSE_DEVICE_STATUS: // "10"
                // 心跳响应，不打印
                mHeartbeatResponseReceived = true;
                return;

            case BanknoteCommand.RESPONSE_START_MONEY: // "3e"
                synchronized (mLock) {
                    mState = State.RECEIVING;
                }
                // 收款中停止心跳，防止 "02" 干扰纸钞识别/接受流程
                stopHeartbeat();
                Log.i(TAG, "StartMoney confirmed → RECEIVING (heartbeat stopped)");
                notifyOnMainThread(() -> {
                    BanknoteReceiverListener l = mListener;
                    if (l != null) l.onStartMoney(true);
                });
                return;

            case BanknoteCommand.RESPONSE_STOP_MONEY: // "5e"
                synchronized (mLock) {
                    if (mState == State.RECEIVING) mState = State.CONNECTED;
                }
                startHeartbeat();
                Log.i(TAG, "StopMoney confirmed → CONNECTED (heartbeat resumed)");
                notifyOnMainThread(() -> {
                    BanknoteReceiverListener l = mListener;
                    if (l != null) l.onStopMoney(true);
                });
                return;

            default:
                break;
        }

        // 提取有效载荷（去除 80/81 前缀）
        String payload = extractPayload(msg);
        if (TextUtils.isEmpty(payload)) {
            Log.e(TAG, "Unknown/empty payload in msg: [" + msg + "]");
            return;
        }

        int value = parseHex(payload);
        if (value < 0) return;

        // 错误码范围: 0x20-0x2F
        if (value >= BanknoteCommand.ERROR_RANGE_START && value <= BanknoteCommand.ERROR_RANGE_END) {
            String errorMsg = BanknoteCommand.getErrorMessage(payload);
            Log.w(TAG, "Banknote error: " + errorMsg);
            notifyOnMainThread(() -> {
                BanknoteReceiverListener l = mListener;
                if (l != null) l.onError(errorMsg);
            });
            return;
        }

        // 面值码范围: 0x40-0x4F
        if (value >= BanknoteCommand.MONEY_RANGE_START && value <= BanknoteCommand.MONEY_RANGE_END) {
            sendMsg(BanknoteCommand.getReceiverCommand()); // 接受纸钞 "02"
            int moneyIndex = value - BanknoteCommand.MONEY_RANGE_START + 1;
            int total = mTotalMoney;
            Log.i(TAG, "Money received: channel=" + moneyIndex);
            notifyOnMainThread(() -> {
                BanknoteReceiverListener l = mListener;
                if (l != null) l.onMoneyReceived(moneyIndex, total);
            });
        }
    }

    /**
     * 提取有效载荷（去除帧头 80/81）
     */
    private String extractPayload(String msg) {
        if (msg == null) return null;
        if (msg.startsWith("818f")) return msg.substring(4);
        if (msg.startsWith("81"))   return msg.substring(2);
        if (msg.startsWith("80"))   return msg.substring(2);
        return msg;
    }

    private int parseHex(String hex) {
        try {
            return Integer.parseInt(hex, 16);
        } catch (Exception e) {
            return -1;
        }
    }

    private void sendMsg(String hex) {
        BanknoteSerialPort port = mSerialPort;
        if (port != null && !TextUtils.isEmpty(hex)) {
            port.send(hex);
            Log.e(TAG, "Send: [" + hex + "]");
        } else {
            Log.e(TAG, "Send skipped (no serial port injected): [" + hex + "]");
        }
    }

    // ==================== 心跳 ====================

    private void startHeartbeat() {
        mMainHandler.removeCallbacks(mHeartbeatRunnable);
        mMainHandler.removeCallbacks(mHeartbeatCheckRunnable);
        mMainHandler.postDelayed(mHeartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void stopHeartbeat() {
        mMainHandler.removeCallbacks(mHeartbeatRunnable);
        mMainHandler.removeCallbacks(mHeartbeatCheckRunnable);
    }

    private void scheduleNextHeartbeat() {
        if (!mDestroyed && isConnected()) {
            mMainHandler.removeCallbacks(mHeartbeatRunnable);
            mMainHandler.postDelayed(mHeartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        }
    }

    // ==================== 握手超时 ====================

    private void scheduleHandshakeTimeout() {
        mMainHandler.removeCallbacks(mHandshakeTimeoutRunnable);
        mMainHandler.postDelayed(mHandshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS);
    }

    private void cancelHandshakeTimeout() {
        mMainHandler.removeCallbacks(mHandshakeTimeoutRunnable);
    }

    // ==================== 连接丢失处理 ====================

    private void handleConnectionLost() {
        synchronized (mLock) {
            if (mState == State.IDLE || mDestroyed) return;
            Log.w(TAG, "Connection lost, prev state=" + mState);
            mState = State.IDLE;
            mTotalMoney = 0;
        }
        stopHeartbeat();
        cancelHandshakeTimeout();
        notifyOnMainThread(() -> {
            BanknoteReceiverListener l = mListener;
            if (l != null) l.onConnected(false);
        });
    }

    // ==================== 工具 ====================

    private void notifyOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mMainHandler.post(action);
        }
    }
}
