package com.dawn.bill;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * LCT纸钞机通信协议指令与常量定义
 */
public class BanknoteCommand {

    private BanknoteCommand() {
    }

    // ===================== 响应码（小写，用于匹配接收数据） =====================

    /** 握手响应 */
    public static final String RESPONSE_HANDSHAKE = "808f";
    /** 设备空闲状态 */
    public static final String RESPONSE_DEVICE_STATUS = "10";
    /** 开始收款确认 */
    public static final String RESPONSE_START_MONEY = "3e";
    /** 停止收款确认 */
    public static final String RESPONSE_STOP_MONEY = "5e";

    // ===================== 数据范围 =====================

    /** 错误码起始值 (0x20) */
    public static final int ERROR_RANGE_START = 0x20;
    /** 错误码结束值 (0x2F) */
    public static final int ERROR_RANGE_END = 0x2F;
    /** 收款码起始值 (0x40) */
    public static final int MONEY_RANGE_START = 0x40;
    /** 收款码结束值 (0x4F) */
    public static final int MONEY_RANGE_END = 0x4F;

    // ===================== 错误码映射 =====================

    private static final Map<String, String> ERROR_MESSAGES;

    static {
        Map<String, String> map = new HashMap<>();
        map.put("20", "马达故障");
        map.put("21", "检验码故障");
        map.put("22", "卡币");
        map.put("23", "纸币移开");
        map.put("24", "纸箱移开");
        map.put("25", "电眼故障");
        map.put("27", "钓鱼");
        map.put("28", "纸箱故障");
        map.put("29", "拒收");
        map.put("2f", "异常情况结束");
        ERROR_MESSAGES = Collections.unmodifiableMap(map);
    }

    /**
     * 根据错误码获取错误描述
     *
     * @param errorCode 小写hex错误码，如 "22"
     * @return 错误描述文本
     */
    public static String getErrorMessage(String errorCode) {
        String msg = ERROR_MESSAGES.get(errorCode);
        return msg != null ? msg : "未知错误(0x" + errorCode + ")";
    }

    // ===================== 发送指令 =====================

    /** 获取状态/握手回应指令 */
    public static String getStatusCommand() {
        return "02";
    }

    /** 接受纸钞确认指令 */
    public static String getReceiverCommand() {
        return "02";
    }

    /** 拒收纸钞指令 */
    public static String getRejectCommand() {
        return "0f";
    }

    /** 开启收款指令 */
    public static String getStartMoneyCommand() {
        return "3e";
    }

    /** 停止收款指令 */
    public static String getStopMoneyCommand() {
        return "5e";
    }
}
