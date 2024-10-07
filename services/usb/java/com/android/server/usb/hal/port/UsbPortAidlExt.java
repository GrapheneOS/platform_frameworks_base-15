package com.android.server.usb.hal.port;

import android.annotation.Nullable;
import android.hardware.usb.UsbManager;
import android.hardware.usb.ext.IUsbExt;
import android.hardware.usb.ext.PortSecurityState;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelableException;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Slog;

class UsbPortAidlExt {
    static final String TAG = UsbPortAidlExt.class.getSimpleName();

    static void setPortSecurityState(IBinder usbHal, String portName,
                                 @android.hardware.usb.ext.PortSecurityState int state,
                                 ResultReceiver callback) {
        IBinder ext;
        try {
            ext = usbHal.getExtension();
        } catch (RemoteException e) {
            sendSpssExceptionResult(new RuntimeException("unable to retrieve USB HAL extension", e), callback);
            return;
        }

        if (ext == null) {
            sendSpssExceptionResult(new RuntimeException("IUsbExt is null"), callback);
            return;
        }

        var halCallback = new android.hardware.usb.ext.IPortSecurityStateCallback.Stub() {
            @Override
            public void onSetPortSecurityStateCompleted(int status, int arg1, String arg2) {
                Slog.d(TAG, "onSetPortSecurityStateCompleted, status: " + status);
                android.os.Bundle b = null;
                if (arg1 != 0 || arg2 != null) {
                    b = new android.os.Bundle();
                    b.putInt("arg1", arg1);
                    b.putString("arg2", arg2);
                }
                callback.send(status, b);
            }

            @Override
            public String getInterfaceHash() {
                return android.hardware.usb.ext.IPortSecurityStateCallback.HASH;
            }

            @Override
            public int getInterfaceVersion() {
                return android.hardware.usb.ext.IPortSecurityStateCallback.VERSION;
            }
        };

        Slog.d(TAG, "setPortSecurityState, port: " + portName + ", state " + state);

        IUsbExt usbExt = IUsbExt.Stub.asInterface(ext);
        try {
            usbExt.setPortSecurityState(portName, state, halCallback);
        } catch (RemoteException e) {
            sendSpssExceptionResult(new RuntimeException("IUsbExt.setPortSecurityState() failed", e), callback);
            return;
        }
    }

    private static void sendSpssExceptionResult(Throwable e, ResultReceiver target) {
        target.send(UsbManager.SET_PORT_SECURITY_STATE_RESULT_CODE_FRAMEWORK_EXCEPTION, createExceptionBundle(e));
        Slog.e(TAG, "", e);
    }

    private static Bundle createExceptionBundle(Throwable e) {
        var b = new android.os.Bundle();
        b.putParcelable(UsbManager.SET_PORT_SECURITY_STATE_EXCEPTION_KEY, new ParcelableException(e));
        return b;
    }
}
