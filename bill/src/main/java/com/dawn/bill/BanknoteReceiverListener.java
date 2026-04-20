package com.dawn.bill;

/**
 * 纸钞机事件回调接口
 * <p>所有回调方法均在主线程执行</p>
 */
public interface BanknoteReceiverListener {

    /**
     * 纸钞机连接状态回调
     *
     * @param connected true-连接成功（握手完成），false-连接失败
     */
    void onConnected(boolean connected);

    /**
     * 开始收款确认回调（设备已确认进入收款状态）
     *
     * @param success true-设备确认成功
     */
    void onStartMoney(boolean success);

    /**
     * 停止收款确认回调（设备已确认停止收款）
     *
     * @param success true-设备确认成功
     */
    void onStopMoney(boolean success);

    /**
     * 收到纸币回调
     *
     * @param moneyIndex 面额索引（1-16，对应纸钞机面额通道编号）
     * @param totalMoney 目标收款金额
     */
    void onMoneyReceived(int moneyIndex, int totalMoney);

    /**
     * 纸钞机错误回调
     *
     * @param errorMsg 错误描述信息
     */
    void onError(String errorMsg);
}
