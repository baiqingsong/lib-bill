package com.dawn.bill;

import android.util.Log;

/**
 * 纸钞支付日志工具类
 * <p>
 * 提供统一的日志开关控制，默认关闭。
 * 调试时通过 {@link #setEnabled(boolean)} 开启，正式发版前保持关闭状态。
 * </p>
 * <p>使用示例（在宿主 App 调试时开启）：</p>
 * <pre>
 *   PaymentLog.setEnabled(true);   // 开启日志
 *   PaymentLog.setEnabled(false);  // 关闭日志（默认）
 * </pre>
 */
public class PaymentLog {

    /** 日志是否开启，默认关闭 */
    private static volatile boolean sEnabled = false;

    /**
     * 开启或关闭纸钞支付日志
     *
     * @param enabled true 开启，false 关闭
     */
    public static void setEnabled(boolean enabled) {
        sEnabled = enabled;
    }

    /**
     * 是否已开启日志
     *
     * @return true 表示已开启
     */
    public static boolean isEnabled() {
        return sEnabled;
    }

    /** @see Log#d(String, String) */
    public static void d(String tag, String msg) {
        if (sEnabled) Log.d(tag, msg);
    }

    /** @see Log#i(String, String) */
    public static void i(String tag, String msg) {
        if (sEnabled) Log.i(tag, msg);
    }

    /** @see Log#w(String, String) */
    public static void w(String tag, String msg) {
        if (sEnabled) Log.w(tag, msg);
    }

    /** @see Log#w(String, String, Throwable) */
    public static void w(String tag, String msg, Throwable tr) {
        if (sEnabled) Log.w(tag, msg, tr);
    }

    /** @see Log#e(String, String) */
    public static void e(String tag, String msg) {
        if (sEnabled) Log.e(tag, msg);
    }

    /** @see Log#e(String, String, Throwable) */
    public static void e(String tag, String msg, Throwable tr) {
        if (sEnabled) Log.e(tag, msg, tr);
    }
}
