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
            Slog.e(TAG, "", e);
            throw new UnsupportedOperationException(e);
        }

        if (ext == null) {
            Slog.d(TAG, "setPortSecurityState: no IUsbExt");
            throw new UnsupportedOperationException();
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
            Slog.e(TAG, "", e);
            throw new android.os.ParcelableException(e);
        }
    }
}
