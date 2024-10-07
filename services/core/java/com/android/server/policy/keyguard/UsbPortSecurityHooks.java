package com.android.server.policy.keyguard;

import android.annotation.Nullable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.ext.settings.UsbPortSecurity;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.UsbPortStatus;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.infra.AndroidFuture;
import com.android.server.ext.SystemErrorNotification;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class UsbPortSecurityHooks {
    private static final String TAG = UsbPortSecurityHooks.class.getSimpleName();
    @Nullable
    private static UsbPortSecurityHooks INSTANCE;

    private final Context context;
    private final Handler handler;
    private final UsbManager usbManager;

    private UsbPortSecurityHooks(Context ctx) {
        this.context = ctx;
        // use a dedicated thread to guarantee that the callbacks do not stall
        var ht = new HandlerThread(TAG);
        ht.start();
        this.handler = ht.getThreadHandler();
        this.usbManager = Objects.requireNonNull(ctx.getSystemService(UsbManager.class));
    }

    private static volatile int isSupportedCached;

    private static boolean isSupported(Context ctx) {
        int cache = isSupportedCached;
        if (cache != 0) {
            return cache > 0;
        }

        boolean res = ctx.getResources().getBoolean(R.bool.config_usbPortSecuritySupported);
        isSupportedCached = res ? 1 : -1;
        return res;
    }

    public static void init(Context ctx) {
        if (!isSupported(ctx)) {
            return;
        }

        var i = new UsbPortSecurityHooks(ctx);
        synchronized (pendingCallbacks) {
            INSTANCE = i;
            for (Runnable cb : pendingCallbacks) {
                Slog.d(TAG, "init: enqueued a pending callback");
                i.handler.post(cb);
            }
            pendingCallbacks.clear();
        }
        i.registerPortChangeReceiver();
    }

    void registerPortChangeReceiver() {
        var receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Slog.d(TAG, "PortChangeReceiver: " + intent + ", extras " + intent.getExtras().deepCopy());
                UsbPortStatus portStatus = intent.getParcelableExtra(UsbManager.EXTRA_PORT_STATUS,
                        UsbPortStatus.class);
                if (portStatus.isConnected()) {
                    ++usbConnectEventCount;
                    Slog.d(TAG, "usbConnectEventCount: " + usbConnectEventCount);
                }
            }
        };
        var filter = new IntentFilter(UsbManager.ACTION_USB_PORT_CHANGED);
        context.registerReceiver(receiver, filter, null, handler);
    }

    private static final ArrayList<Runnable> pendingCallbacks = new ArrayList<>();

    public static void onKeyguardShowingStateChanged(Context ctx, boolean showing, int userId) {
        if (!isSupported(ctx)) {
            return;
        }

        UsbPortSecurityHooks instance;
        synchronized (pendingCallbacks) {
            instance = INSTANCE;
            if (instance == null) {
                // UsbService hasn't completed initialization yet, delay the callback until then
                Slog.d(TAG, "onKeyguardShowingStateChanged: adding pending callback: showing: " + showing + " userId " + userId);
                pendingCallbacks.add(() -> onKeyguardShowingStateChanged(ctx, showing, userId));
                return;
            }
        }

        instance.handler.post(() -> instance.onKeyguardShowingStateChangedInner(ctx, showing, userId));
    }

    private boolean keyguardDismissedAtLeastOnce;
    private Boolean prevKeyguardShowing; // intentionally using boxed boolean to have a null value
    private long keyguardShowingChangeCount;

    private int usbConnectEventCountBeforeLocked;
    private int usbConnectEventCount;

    void onKeyguardShowingStateChangedInner(Context ctx, boolean showing, int userId) {
        int setting = UsbPortSecurity.MODE_SETTING.get();

        Slog.d(TAG, "onKeyguardShowingStateChanged, showing " + showing + ", userId " + userId
                + ", modeSetting " + setting);

        Boolean showingB = Boolean.valueOf(showing);
        if (prevKeyguardShowing == showingB) {
            Slog.d(TAG, "onKeyguardShowingStateChangedInner: duplicate callback, ignoring");
            return;
        }
        prevKeyguardShowing = showingB;
        ++keyguardShowingChangeCount;

        if (setting == UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED
              || (keyguardDismissedAtLeastOnce && setting == UsbPortSecurity.MODE_CHARGING_ONLY_WHEN_LOCKED_AFU))
        {
            if (showing) {
                setSecurityStateForAllPorts(android.hardware.usb.ext.PortSecurityState.CHARGING_ONLY);
                usbConnectEventCountBeforeLocked = usbConnectEventCount;
            } else {
                boolean forceReconnect = false;
                if (!keyguardDismissedAtLeastOnce) {
                    for (UsbPort port : usbManager.getPorts()) {
                        UsbPortStatus s = port.getStatus();
                        if (s == null || s.isConnected()) {
                            // at boot-time, "port connected" event might not be delivered if the
                            // event fires before UsbService is initialized, which breaks the
                            // usbConnectEventCountBeforeLocked check below
                            forceReconnect = true;
                            break;
                        }
                    }
                }

                if (!forceReconnect && usbConnectEventCountBeforeLocked == usbConnectEventCount) {
                    setSecurityStateForAllPorts(android.hardware.usb.ext.PortSecurityState.ENABLED);
                } else {
                    // Turn USB ports off and on to trigger reconnection of devices that were connected
                    // in charging-only state. Simply enabling the data path is not enough in some
                    // advanced scenarios, e.g. when port alt mode or port role switching are used.
                    Slog.d(TAG, "toggling USB ports");
                    setSecurityStateForAllPorts(android.hardware.usb.ext.PortSecurityState.DISABLED);
                    final long curShowingChangeCount = keyguardShowingChangeCount;
                    final long delayMs = 1500;
                    handler.postDelayed(() -> {
                        if (keyguardShowingChangeCount == curShowingChangeCount) {
                            setSecurityStateForAllPorts(android.hardware.usb.ext.PortSecurityState.ENABLED);
                        } else {
                            Slog.d(TAG, "showingChangeCount changed, skipping delayed enable");
                        }
                    }, delayMs);
                }
            }
        }

        if (userId == UserHandle.USER_SYSTEM && !showing) {
            keyguardDismissedAtLeastOnce = true;
        }
    }

    private void setSecurityStateForAllPorts(int state) {
        Slog.d(TAG, "setSecurityStateForAllPorts: " + state);

        List<UsbPort> ports = usbManager.getPorts();
        AndroidFuture[] results = new AndroidFuture[ports.size()];

        for (int i = 0, m = ports.size(); i < m; ++i) {
            UsbPort port = ports.get(i);
            var result = new AndroidFuture<Pair<Integer, Bundle>>();
            results[i] = result;

            var resultReceiver = new ResultReceiver(null) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    result.complete(null);
                    if (resultCode == android.hardware.usb.ext.IUsbExt.NO_ERROR) {
                        return;
                    }
                    var b = new StringBuilder("setPortSecurityState failed, resultCode: ");
                    b.append(resultCode);
                    if (resultData != null) {
                        b.append(", resultData: ");
                        b.append(resultData.toStringDeep());
                    }
                    b.append(", ");
                    b.append(port);
                    showErrorNotif(b.toString());
                }
            };

            usbManager.setPortSecurityState(port, state, resultReceiver);
            results[i] = result;
        }

        // wait for result callbacks to avoid potential race conditions
        try {
            CompletableFuture.allOf(results).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            showErrorNotif(Log.getStackTraceString(e));
        }
    }

    private void showErrorNotif(String msg) {
        String type = "error in USB-C port security feature";
        String title = context.getString(R.string.usb_port_security_error_title);
        new SystemErrorNotification(type, title, msg).show(context);
    }
}
