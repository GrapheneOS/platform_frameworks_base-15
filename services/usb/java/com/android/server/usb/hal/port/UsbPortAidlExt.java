package com.android.server.usb.hal.port;

import android.annotation.Nullable;
import android.hardware.usb.ext.IUsbExt;
import android.hardware.usb.ext.PortSecurityState;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.Slog;

import static com.android.server.usb.UsbPortSecurity.sendSpssExceptionResult;

class UsbPortAidlExt {
    static final String TAG = UsbPortAidlExt.class.getSimpleName();

    static void setPortSecurityState(@Nullable IBinder usbHal, String portName,
                                     @PortSecurityState int state,
                                     ResultReceiver callback) {
        if (usbHal == null) {
            sendSpssExceptionResult(new RuntimeException("USB HAL is null"), callback);
            return;
        }

        final IBinder ext;
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
}
