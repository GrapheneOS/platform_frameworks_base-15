package com.android.server.pm.ext;

import android.Manifest;
import android.content.pm.ServiceInfo;

import com.android.internal.gmscompat.GmcMediaProjectionService;
import com.android.internal.gmscompat.GmsHooks;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedService;
import com.android.internal.pm.pkg.component.ParsedServiceImpl;
import com.android.internal.pm.pkg.component.ParsedUsesPermissionImpl;
import com.android.internal.pm.pkg.parsing.ParsingPackage;

import java.util.Collections;
import java.util.List;

class GmsCoreHooks {

    static class ParsingHooks extends GmsCompatPkgParsingHooks {

        @Override
        public boolean shouldSkipPermissionDefinition(ParsedPermission p) {
            return shouldSkipPermissionDefinition(p.getName());
        }

        static boolean shouldSkipPermissionDefinition(String name) {
            switch (name) {
                // These permissions are declared in GmsCompat app instead. They were moved there
                // because of an issue with permissions that have "normal" protectionLevel. If
                // the app that declares a "normal" permission is installed after an app that
                // requests that permission, the permission will not be granted. GmsCompat app
                // is a preinstalled app, it's always present.
                case "com.google.android.c2dm.permission.RECEIVE":
                case "com.google.android.providers.gsf.permission.READ_GSERVICES":
                // This permission is declared in GSF on regular Android. It was moved to GmsCompat
                // app to avoid the need to install GSF, which misbehaves on SDK 35+ due
                // to signature mismatch between itself and GmsCore (GSF and GmsCore use a sharedUid
                // on regular Android)
                case "com.google.android.c2dm.permission.SEND":
                    return true;
                default:
                    return false;
            }
        }

        @Override
        public List<ParsedUsesPermissionImpl> addUsesPermissions() {
            var res = super.addUsesPermissions();
            var l = createUsesPerms(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            res.addAll(l);
            return res;
        }

        @Override
        public List<ParsedService> addServices(ParsingPackage pkg) {
            ParsedServiceImpl s = createService(pkg, GmcMediaProjectionService.class.getName());
            s.setProcessName(GmsHooks.PERSISTENT_GmsCore_PROCESS);
            s.setForegroundServiceType(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            s.setExported(false);

            return Collections.singletonList(s);
        }
    }
}
