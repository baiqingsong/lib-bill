package com.dawn.libbanknotelct;

import android.os.Bundle;
import android.util.Log;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

import com.dawn.bill.BanknoteManager;
import com.dawn.bill.BanknoteReceiverListener;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private BanknoteManager mBanknoteManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mBanknoteManager = BanknoteManager.getInstance(this);
        mBanknoteManager.setListener(new BanknoteReceiverListener() {
            @Override
            public void onConnected(boolean connected) {
                Log.d(TAG, "onConnected: " + connected);
            }

            @Override
            public void onStartMoney(boolean success) {
                Log.d(TAG, "onStartMoney: " + success);
            }

            @Override
            public void onStopMoney(boolean success) {
                Log.d(TAG, "onStopMoney: " + success);
            }

            @Override
            public void onMoneyReceived(int moneyIndex, int totalMoney) {
                Log.d(TAG, "onMoneyReceived index=" + moneyIndex + ", total=" + totalMoney);
            }

            @Override
            public void onError(String errorMsg) {
                Log.e(TAG, "onError: " + errorMsg);
            }
        });
    }

    public void startPort(View view) {
        mBanknoteManager.startPort(4);
    }

    public void startMoney(View view) {
        mBanknoteManager.startMoney(5);
    }

    public void stopMoney(View view) {
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
