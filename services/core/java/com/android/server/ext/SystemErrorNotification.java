package com.android.server.ext;

import android.annotation.CurrentTimeMillisLong;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.ext.LogViewerApp;
import android.util.Slog;

public class SystemErrorNotification {
    static final String TAG = SystemErrorNotification.class.getSimpleName();

    public @CurrentTimeMillisLong long when = System.currentTimeMillis();
    public final String type;
    public final String title;
    public final String message;
    public boolean showReportButton = true;

    public SystemErrorNotification(String type, String message) {
        this(type, type, message);
    }

    public SystemErrorNotification(String type, String title, String message) {
        this.type = type;
        this.title = title;
        this.message = message;
    }

    public void show(@Nullable Context ctx) {
        var i = LogViewerApp.createBaseErrorReportIntent(message);
        i.putExtra(Intent.EXTRA_TITLE, title);
        i.putExtra(LogViewerApp.EXTRA_ERROR_TYPE, type);
        i.putExtra(LogViewerApp.EXTRA_SHOW_REPORT_BUTTON, showReportButton);
        Slog.e(TAG, type + ", title: " + title + ", message: " + message);
        SystemJournalNotif.show(ctx, when, title, i);
    }
}
