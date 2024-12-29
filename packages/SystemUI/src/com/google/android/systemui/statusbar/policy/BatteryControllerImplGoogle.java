package com.google.android.systemui.statusbar.policy;

import android.content.Context;
import android.ext.power.BatteryChargeLimit;
import android.os.Handler;
import android.os.PowerManager;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.power.EnhancedEstimates;
import com.android.systemui.statusbar.policy.BatteryControllerImpl;
import com.android.systemui.statusbar.policy.BatteryControllerLogger;

import static android.os.BatteryManager.CHARGING_POLICY_ADAPTIVE_LONGLIFE;

public class BatteryControllerImplGoogle extends BatteryControllerImpl {

    public BatteryControllerImplGoogle(Context context,
                                       EnhancedEstimates enhancedEstimates,
                                       PowerManager powerManager,
                                       BroadcastDispatcher broadcastDispatcher,
                                       DemoModeController demoModeController,
                                       DumpManager dumpManager,
                                       BatteryControllerLogger logger,
                                       Handler mainHandler,
                                       Handler bgHandler) {
        super(context, enhancedEstimates, powerManager, broadcastDispatcher, demoModeController,
                dumpManager, logger, mainHandler, bgHandler);
    }

    @Override
    protected boolean isBatteryDefenderMode(int chargingStatus) {
        if (chargingStatus != CHARGING_POLICY_ADAPTIVE_LONGLIFE) {
            return false;
        }

        boolean isChargeLimitEnabled = BatteryChargeLimit.isChargeLimitEnabled(mContext);
        if (isChargeLimitEnabled) {
            return mLevel >= BatteryChargeLimit.CHARGE_LEVEL;
        }
        return false;
    }
}
