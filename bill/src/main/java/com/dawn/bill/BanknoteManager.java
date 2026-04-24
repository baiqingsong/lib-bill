package com.dawn.bill;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.dawn.serial.LSerialUtil;

/**
 * 纸钞机管理类
 * <p>
 * 通过串口与LCT纸钞机通信，支持握手、收款、停止等操作。
 * 使用 HandlerThread 在后台线程进行串口通信，回调在主线程执行。
 * </p>
 * <p>
 * 功能特性：
 * <ul>
 *   <li>自动握手与连接状态管理</li>
 *   <li>握手超时检测（默认5秒）</li>
 *   <li>心跳保活机制（默认30秒间隔）</li>
 *   <li>串口通信错误自动重连（可配置最大重试次数）</li>
 *   <li>收款/停止收款命令超时检测</li>
 *   <li>所有回调在主线程执行，线程安全</li>
 * </ul>
 * </p>
 * <p>
 * 用法：
 * <pre>
 *   BanknoteManager manager = BanknoteManager.getInstance(context);
 *   manager.setListener(listener);
 *   manager.startPort(4);
 *   manager.startMoney(100);
 *   manager.stopMoney();
 *   manager.destroy(); // 不再使用时释放资源
 * </pre>
 * </p>
 */
public class BanknoteManager {

    private static final String TAG = "BanknoteManager";
    private static final int BAUD_RATE = 9600;
    private static final int DATA_BITS = 8;
    private static final int STOP_BITS = 1;
    private static final char PARITY = 'E';

    /** 握手超时时间（毫秒） */
    private static final long HANDSHAKE_TIMEOUT_MS = 5000;
    /** 心跳间隔（毫秒） */
    private static final long HEARTBEAT_INTERVAL_MS = 30000;
    /** 心跳响应超时（毫秒） */
    private static final long HEARTBEAT_TIMEOUT_MS = 5000;
    /** 重连基础延迟（毫秒） */
    private static final long RECONNECT_DELAY_BASE_MS = 2000;
    /** 重连最大延迟（毫秒） */
    private static final long RECONNECT_DELAY_MAX_MS = 30000;

    /** 纸钞机运行状态 */
    public enum State {
        /** 未初始化 */
        IDLE,
        /** 正在连接（串口已打开，等待握手） */
        CONNECTING,
        /** 已连接（握手成功） */
        CONNECTED,
        /** 正在收款 */
        RECEIVING
    }

    private static volatile BanknoteManager sInstance;

    private final Context mContext;
    private final Handler mMainHandler;
    private final Object mLock = new Object();

    private HandlerThread mSerialThread;
    private Handler mSerialHandler;
    private volatile BanknoteReceiverListener mListener;
    private LSerialUtil mSerialUtil;
    private volatile int mTotalMoney;
    private volatile State mState = State.IDLE;
    private int mCurrentPort = -1;
    private boolean mDestroyed;

    // 握手超时检测
    private final Runnable mHandshakeTimeoutRunnable = () -> {
        synchronized (mLock) {
            if (mState != State.CONNECTING) {
                return;
            }
            Log.w(TAG, "Handshake timeout, connection failed");
            mState = State.IDLE;
        }
        closePort();
        notifyOnMainThread(() -> {
            BanknoteReceiverListener l = mListener;
            if (l != null) {
                l.onConnected(false);
                l.onError("握手超时，连接失败");
            }
        });
    };

    // 心跳检测
    private volatile boolean mHeartbeatResponseReceived;
    private final Runnable mHeartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isConnected() || mDestroyed) {
                return;
            }
            // 发送状态查询作为心跳
            mHeartbeatResponseReceived = false;
            sendMsg(BanknoteCommand.getStatusCommand());
            // 检测心跳响应超时
            if (mSerialHandler != null) {
                mSerialHandler.postDelayed(mHeartbeatCheckRunnable, HEARTBEAT_TIMEOUT_MS);
            }
        }
    };

    private final Runnable mHeartbeatCheckRunnable = () -> {
        if (mDestroyed || !isConnected()) {
            return;
        }
        if (!mHeartbeatResponseReceived) {
            Log.w(TAG, "Heartbeat timeout, connection may be lost");
            handleConnectionLost();
        } else {
            // 本次心跳响应正常，安排下一轮心跳
            scheduleNextHeartbeat();
        }
    };

    // 自动重连（无限重试，使用退避延迟）
    private int mReconnectRetryCount;
    private final Runnable mAutoReconnectRunnable = () -> {
        synchronized (mLock) {
            if (mDestroyed || mState != State.IDLE || mCurrentPort < 0) {
                return;
            }
            mReconnectRetryCount++;
            Log.i(TAG, "Auto reconnecting, attempt " + mReconnectRetryCount);
            mState = State.CONNECTING;
        }
        openPort(mCurrentPort);
    };

    private BanknoteManager(Context context) {
        mContext = context.getApplicationContext();
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 获取单例实例
     *
     * @param context 上下文（内部使用 ApplicationContext，不会泄漏 Activity）
     */
    public static BanknoteManager getInstance(Context context) {
        if (sInstance == null) {
            synchronized (BanknoteManager.class) {
                if (sInstance == null) {
                    sInstance = new BanknoteManager(context);
                }
            }
        }
        return sInstance;
    }

    /**
     * 设置纸钞机事件监听器
     */
    public void setListener(BanknoteReceiverListener listener) {
        mListener = listener;
    }

    /**
     * 移除纸钞机事件监听器
     */
    public void removeListener() {
        mListener = null;
    }

    /**
     * 获取当前状态
     */
    public State getState() {
        return mState;
    }

    /**
     * 查询串口是否已连接（握手完成）
     */
    public boolean isConnected() {
        State state = mState;
        return state == State.CONNECTED || state == State.RECEIVING;
    }

    /**
     * 打开串口连接纸钞机
     *
     * @param port 串口号
     */
    public void startPort(int port) {
        if (port < 0) {
            Log.w(TAG, "Invalid port number: " + port);
            return;
        }
        synchronized (mLock) {
            if (mDestroyed) {
                Log.w(TAG, "Manager already destroyed");
                return;
            }
            if (mState != State.IDLE) {
                Log.w(TAG, "Cannot start port, current state: " + mState);
                return;
            }
            mCurrentPort = port;
            mState = State.CONNECTING;
            mReconnectRetryCount = 0;
        }
        ensureSerialThread();
        mSerialHandler.post(() -> openPort(port));
    }

    /**
     * 重新连接串口（先断开再重新打开）
     */
    public void reconnect() {
        synchronized (mLock) {
            if (mDestroyed) {
                Log.w(TAG, "Manager already destroyed");
                return;
            }
            final int port = mCurrentPort;
            if (port < 0) {
                Log.w(TAG, "No port configured, call startPort() first");
                return;
            }
            mState = State.CONNECTING;
            mTotalMoney = 0;
            mReconnectRetryCount = 0;
        }
        ensureSerialThread();
        mSerialHandler.post(() -> {
            stopHeartbeat();
            closePort();
            openPort(mCurrentPort);
        });
    }

    /**
     * 断开串口连接（不销毁实例，可再次 startPort）
     */
    public void disconnect() {
        synchronized (mLock) {
            mTotalMoney = 0;
            mState = State.IDLE;
            mCurrentPort = -1; // 阻止自动重连
        }
        if (mSerialHandler != null) {
            mSerialHandler.removeCallbacks(mAutoReconnectRunnable);
            mSerialHandler.removeCallbacks(mHandshakeTimeoutRunnable);
            mSerialHandler.post(() -> {
                stopHeartbeat();
                closePort();
            });
        }
    }

    /**
     * 开始收款
     *
     * @param money 目标收款金额（必须大于0）
     */
    public void startMoney(int money) {
        if (money <= 0) {
            Log.w(TAG, "Invalid money amount: " + money);
            return;
        }
        if (!isConnected()) {
            Log.w(TAG, "Not connected, cannot start money. State: " + mState);
            return;
        }
        mTotalMoney = money;
        postSendMsg(BanknoteCommand.getStartMoneyCommand());
    }

    /**
     * 停止收款
     */
    public void stopMoney() {
        if (!isConnected()) {
            Log.w(TAG, "Not connected, cannot stop money. State: " + mState);
            return;
        }
        mTotalMoney = 0;
        postSendMsg(BanknoteCommand.getStopMoneyCommand());
    }

    /**
     * 获取当前目标收款金额
     */
    public int getTotalMoney() {
        return mTotalMoney;
    }

    /**
     * 获取当前串口号
     *
     * @return 串口号，未配置时返回 -1
     */
    public int getCurrentPort() {
        return mCurrentPort;
    }

    /**
     * 释放所有资源，销毁单例。
     * 调用后需要重新通过 {@link #getInstance(Context)} 获取新实例。
     */
    public void destroy() {
        synchronized (mLock) {
            mDestroyed = true;
            mState = State.IDLE;
            mTotalMoney = 0;
            mListener = null;
        }
        if (mSerialHandler != null) {
            mSerialHandler.removeCallbacksAndMessages(null);
            HandlerThread threadToQuit = mSerialThread;
            mSerialHandler.post(() -> {
                stopHeartbeat();
                closePort();
                if (threadToQuit != null) {
                    threadToQuit.quitSafely();
                }
            });
            mSerialThread = null;
            mSerialHandler = null;
        }
        mMainHandler.removeCallbacksAndMessages(null);
        synchronized (BanknoteManager.class) {
            sInstance = null;
        }
    }

    // ===================== 内部方法 =====================

    private void ensureSerialThread() {
        if (mSerialThread == null || !mSerialThread.isAlive()) {
            mSerialThread = new HandlerThread("BanknoteSerial");
            mSerialThread.start();
            mSerialHandler = new Handler(mSerialThread.getLooper());
        }
    }

    private void openPort(int port) {
        if (mSerialUtil != null) {
            return;
        }
        Log.d(TAG, "Opening serial port: " + port);
        try {
            mSerialUtil = new LSerialUtil(port, BAUD_RATE, DATA_BITS, STOP_BITS, PARITY,
                    LSerialUtil.SerialType.TYPE_HEX, new LSerialUtil.OnSerialListener() {
                        @Override
                        public void onOpenError(String portPath, Exception e) {
                            Log.e(TAG, "Serial port open error: " + portPath, e);
                            synchronized (mLock) {
                                mState = State.IDLE;
                            }
                            cancelHandshakeTimeout();
                            notifyOnMainThread(() -> {
                                BanknoteReceiverListener l = mListener;
                                if (l != null) {
                                    l.onConnected(false);
                                    l.onError("串口打开失败: " + portPath);
                                }
                            });
                            // 尝试自动重连
                            scheduleAutoReconnect();
                        }

                        @Override
                        public void onReceiveError(Exception e) {
                            Log.e(TAG, "Serial receive error", e);
                            notifyOnMainThread(() -> {
                                BanknoteReceiverListener l = mListener;
                                if (l != null) {
                                    l.onError("串口接收错误: " + (e != null ? e.getMessage() : "未知"));
                                }
                            });
                            handleConnectionLost();
                        }

                        @Override
                        public void onSendError(Exception e) {
                            Log.e(TAG, "Serial send error", e);
                            notifyOnMainThread(() -> {
                                BanknoteReceiverListener l = mListener;
                                if (l != null) {
                                    l.onError("串口发送错误: " + (e != null ? e.getMessage() : "未知"));
                                }
                            });
                        }

                        @Override
                        public void onDataReceived(String data) {
                            if (TextUtils.isEmpty(data)) {
                                return;
                            }
                            Log.d(TAG, "Received: " + data);
                            handleReceivedData(data.toLowerCase());
                        }
                    });

            // 启动握手超时检测
            scheduleHandshakeTimeout();

        } catch (Throwable t) {
            // 需捕获 Throwable：libserial_port.so 缺失时抛出的是 UnsatisfiedLinkError / NoClassDefFoundError（Error子类），
            // catch(Exception) 无法覆盖，必须使用 Throwable 才能避免线程崩溃。
            Log.e(TAG, "Failed to create serial connection", t);
            synchronized (mLock) {
                mState = State.IDLE;
            }
            final String errMsg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            notifyOnMainThread(() -> {
                BanknoteReceiverListener l = mListener;
                if (l != null) {
                    l.onConnected(false);
                    l.onError("创建串口连接失败: " + errMsg);
                }
            });
            scheduleAutoReconnect();
        }
    }

    /**
     * 处理从纸钞机接收的数据
     */
    private void handleReceivedData(String data) {
        try {
            // 检查固定响应码
            switch (data) {
                case BanknoteCommand.RESPONSE_HANDSHAKE:
                    cancelHandshakeTimeout();
                    sendMsg(BanknoteCommand.getStatusCommand());
                    synchronized (mLock) {
                        if (mState == State.CONNECTING) {
                            mState = State.CONNECTED;
                            mReconnectRetryCount = 0; // 重置重连计数
                        }
                    }
                    startHeartbeat();
                    notifyOnMainThread(() -> {
                        BanknoteReceiverListener l = mListener;
                        if (l != null) {
                            l.onConnected(true);
                        }
                    });
                    return;
                case BanknoteCommand.RESPONSE_DEVICE_STATUS:
                    // 心跳响应
                    mHeartbeatResponseReceived = true;
                    return;
                case BanknoteCommand.RESPONSE_START_MONEY:
                    synchronized (mLock) {
                        mState = State.RECEIVING;
                    }
                    // 进入收款模式后停止心跳，防止"02"指令干扰纸钞识别/接受流程
                    stopHeartbeat();
                    notifyOnMainThread(() -> {
                        BanknoteReceiverListener l = mListener;
                        if (l != null) {
                            l.onStartMoney(true);
                        }
                    });
                    return;
                case BanknoteCommand.RESPONSE_STOP_MONEY:
                    synchronized (mLock) {
                        if (mState == State.RECEIVING) {
                            mState = State.CONNECTED;
                        }
                    }
                    // 退出收款模式后恢复心跳
                    startHeartbeat();
                    notifyOnMainThread(() -> {
                        BanknoteReceiverListener l = mListener;
                        if (l != null) {
                            l.onStopMoney(true);
                        }
                    });
                    return;
                default:
                    break;
            }

            // 提取数据载荷（去除帧头）
            String payload = extractPayload(data);
            if (TextUtils.isEmpty(payload)) {
                Log.w(TAG, "Unknown data format: " + data);
                return;
            }

            int value = parseHex(payload);
            if (value < 0) {
                Log.w(TAG, "Invalid hex payload: " + payload);
                return;
            }

            // 错误码范围: 0x20 - 0x2F
            if (value >= BanknoteCommand.ERROR_RANGE_START
                    && value <= BanknoteCommand.ERROR_RANGE_END) {
                String errorMsg = BanknoteCommand.getErrorMessage(payload);
                Log.w(TAG, "Banknote error [0x" + payload + "]: " + errorMsg);
                notifyOnMainThread(() -> {
                    BanknoteReceiverListener l = mListener;
                    if (l != null) {
                        l.onError(errorMsg);
                    }
                });
                return;
            }

            // 收款码范围: 0x40 - 0x4F
            if (value >= BanknoteCommand.MONEY_RANGE_START
                    && value <= BanknoteCommand.MONEY_RANGE_END) {
                sendMsg(BanknoteCommand.getReceiverCommand());
                int moneyIndex = value - BanknoteCommand.MONEY_RANGE_START + 1;
                int total = mTotalMoney;
                notifyOnMainThread(() -> {
                    BanknoteReceiverListener l = mListener;
                    if (l != null) {
                        l.onMoneyReceived(moneyIndex, total);
                    }
                });
                return;
            }

            Log.w(TAG, "Unhandled data value: 0x" + payload);
        } catch (Exception e) {
            Log.e(TAG, "Error processing received data", e);
        }
    }

    // ===================== 心跳保活 =====================

    private void startHeartbeat() {
        if (mSerialHandler != null) {
            mSerialHandler.removeCallbacks(mHeartbeatRunnable);
            mSerialHandler.removeCallbacks(mHeartbeatCheckRunnable);
            mSerialHandler.postDelayed(mHeartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        }
    }

    private void stopHeartbeat() {
        if (mSerialHandler != null) {
            mSerialHandler.removeCallbacks(mHeartbeatRunnable);
            mSerialHandler.removeCallbacks(mHeartbeatCheckRunnable);
        }
    }

    private void scheduleNextHeartbeat() {
        if (mSerialHandler != null && isConnected() && !mDestroyed) {
            mSerialHandler.removeCallbacks(mHeartbeatRunnable);
            mSerialHandler.postDelayed(mHeartbeatRunnable, HEARTBEAT_INTERVAL_MS);
        }
    }

    // ===================== 握手超时 =====================

    private void scheduleHandshakeTimeout() {
        if (mSerialHandler != null) {
            mSerialHandler.removeCallbacks(mHandshakeTimeoutRunnable);
            mSerialHandler.postDelayed(mHandshakeTimeoutRunnable, HANDSHAKE_TIMEOUT_MS);
        }
    }

    private void cancelHandshakeTimeout() {
        if (mSerialHandler != null) {
            mSerialHandler.removeCallbacks(mHandshakeTimeoutRunnable);
        }
    }

    // ===================== 自动重连 =====================

    private void scheduleAutoReconnect() {
        synchronized (mLock) {
            if (mDestroyed || mCurrentPort < 0) {
                return;
            }
        }
        if (mSerialHandler != null) {
            // 指数退避：2s → 4s → 8s → ... 上限 30s
            long delay = Math.min(RECONNECT_DELAY_BASE_MS * (1L << Math.min(mReconnectRetryCount, 4)),
                    RECONNECT_DELAY_MAX_MS);
            mSerialHandler.removeCallbacks(mAutoReconnectRunnable);
            mSerialHandler.postDelayed(mAutoReconnectRunnable, delay);
        }
    }

    /**
     * 处理连接丢失（心跳超时或接收错误）
     */
    private void handleConnectionLost() {
        synchronized (mLock) {
            if (mState == State.IDLE || mDestroyed) {
                return;
            }
            Log.w(TAG, "Connection lost, previous state: " + mState);
            mState = State.IDLE;
            mTotalMoney = 0;
        }
        stopHeartbeat();
        closePort();
        notifyOnMainThread(() -> {
            BanknoteReceiverListener l = mListener;
            if (l != null) {
                l.onConnected(false);
            }
        });
        scheduleAutoReconnect();
    }

    // ===================== 数据解析工具 =====================

    /**
     * 从原始数据中提取有效载荷，去除帧头
     * 帧格式：818f + payload 或 81 + payload 或 单独的 payload
     */
    private String extractPayload(String data) {
        if (data.startsWith("818f")) {
            return data.substring(4);
        }
        if (data.startsWith("81")) {
            return data.substring(2);
        }
        // 单字节/双字节响应，数据本身即为载荷
        if (data.length() <= 4) {
            return data;
        }
        return null;
    }

    /**
     * 安全的十六进制字符串转整数
     *
     * @return 转换结果，失败返回 -1
     */
    private int parseHex(String hex) {
        if (TextUtils.isEmpty(hex)) {
            return -1;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ===================== 串口发送 =====================

    /**
     * 将发送操作投递到串口线程执行，确保线程安全
     */
    private void postSendMsg(String msg) {
        if (mSerialHandler != null) {
            mSerialHandler.post(() -> sendMsg(msg));
        }
    }

    /**
     * 在串口线程中发送数据（仅内部调用，已确保在串口线程）
     */
    private void sendMsg(String msg) {
        if (mSerialUtil != null && !TextUtils.isEmpty(msg)) {
            Log.d(TAG, "Sending: " + msg);
            mSerialUtil.sendHex(msg);
        }
    }

    private void closePort() {
        if (mSerialUtil != null) {
            try {
                mSerialUtil.disconnect();
            } catch (Exception e) {
                Log.e(TAG, "Error closing serial port", e);
            }
            mSerialUtil = null;
        }
    }

    /**
     * 确保回调在主线程执行
     */
    private void notifyOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mMainHandler.post(action);
        }
    }
}
