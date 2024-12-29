package com.android.systemui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.ext.power.BatteryChargeLimit;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

import vendor.google.google_battery.IGoogleBattery;

public class BootReceiver extends BroadcastReceiver {
    static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!context.getUser().isSystem()) {
            return;
        }

        if (BatteryChargeLimit.isChargeLimitEnabled(context)) {
            setChargingPolicy(IGoogleBattery.BatteryChargingPolicy.LONGLIFE);
        }
    }

    private static void setChargingPolicy(int policy) {
        String svc = IGoogleBattery.DESCRIPTOR + "/default";
        IBinder binder = ServiceManager.getService(svc);
        if (binder == null) {
            Log.d(TAG, svc + " is not available");
            return;
        }
        var service = IGoogleBattery.Stub.asInterface(binder);
        try {
            service.setChargingPolicy(policy);
            Log.d(TAG, "setChargingPolicy to " + policy);
        } catch (RemoteException e) {
            Log.e(TAG, "", e);
        }
    }
}
