package com.android.server.usb;

import android.annotation.Nullable;
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbPort;
import android.hardware.usb.ext.IUsbExt;
import android.hardware.usb.ext.PortSecurityState;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelableException;
import android.os.ResultReceiver;
import android.os.SystemProperties;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.server.ext.SystemErrorNotification;

import java.util.concurrent.atomic.AtomicInteger;

public class UsbPortSecurity {
    private static final String TAG = UsbPortSecurity.class.getSimpleName();

    static void setSecurityStateForAllPorts(Context ctx,
            @Nullable UsbPortManager portManager,
            @PortSecurityState int state, ResultReceiver callback) {
        setDenyNewUsb2(ctx, state != android.hardware.usb.ext.PortSecurityState.ENABLED);

        if (portManager == null) {
            sendSpssExceptionResult(new RuntimeException("UsbPortManager is null"), callback);
            showErrorNotif(ctx, "UsbPortManager is null");
            return;
        }

        UsbPort[] ports = portManager.getPorts();
        final int numPorts = ports.length;

        Pair<Integer, Bundle>[] results = new Pair[numPorts];
        var numResults = new AtomicInteger();

        final long token = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < numPorts; ++i) {
                final int index = i;
                String portId = ports[index].getId();

                ResultReceiver portResultReceiver = new ResultReceiver(null) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (resultCode != android.hardware.usb.ext.IUsbExt.NO_ERROR) {
                            var b = new StringBuilder("setPortSecurityState failed, port: ");
                            b.append(portId);
                            b.append(", resultCode: ");
                            b.append(resultCode);
                            if (resultData != null) {
                                b.append(", resultData: ");
                                b.append(resultData.toStringDeep());
                            }
                            showErrorNotif(ctx, b.toString());
                        }

                        synchronized (results) {
                            results[index] = new Pair<>(Integer.valueOf(resultCode), resultData);
                            if (numResults.incrementAndGet() == numPorts) {
                                sendOverallResult();
                            }
                        }
                    }

                    private void sendOverallResult() {
                        int overallResult = IUsbExt.NO_ERROR;
                        Bundle resultsData = null;
                        for (int j = 0; j < numPorts; ++j) {
                            Pair<Integer, Bundle> result = results[j];
                            int resultCode = result.first.intValue();
                            if (resultCode == IUsbExt.NO_ERROR) {
                                continue;
                            }
                            if (overallResult == IUsbExt.NO_ERROR) {
                                overallResult = resultCode;
                            }
                            if (resultsData == null) {
                                resultsData = new Bundle();
                            }
                            resultsData.putBundle(ports[j].getId(), result.second);
                        }
                        callback.send(overallResult, resultsData);
                    }
                };

                portManager.setPortSecurityState(portId, state, portResultReceiver);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private static void setDenyNewUsb2(Context ctx, boolean enabled) {
        String prop = "security.deny_new_usb2";
        String val = enabled ? "1" : "0";
        try {
            SystemProperties.set(prop, val);
            Slog.d(TAG, "set " + prop + " to " + val);
        } catch (RuntimeException e) {
            String msg = "unable to set " + prop + " to " + val + ":\n" + Log.getStackTraceString(e);
            showErrorNotif(ctx, msg);
        }
    }

    public static void sendSpssExceptionResult(Throwable e, ResultReceiver target) {
        target.send(UsbManager.SET_PORT_SECURITY_STATE_RESULT_CODE_FRAMEWORK_EXCEPTION, createExceptionBundle(e));
        Slog.e(TAG, "", e);
    }

    public static Bundle createExceptionBundle(Throwable e) {
        var b = new Bundle();
        b.putParcelable(UsbManager.SET_PORT_SECURITY_STATE_EXCEPTION_KEY, new ParcelableException(e));
        return b;
    }

    private static void showErrorNotif(Context context, String msg) {
        String type = "error in USB-C port security feature";
        String title = context.getString(com.android.internal.R.string.usb_port_security_error_title);
        new SystemErrorNotification(type, title, msg).show(context);
    }
}
