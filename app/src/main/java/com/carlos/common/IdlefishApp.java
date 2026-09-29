package com.carlos.common;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.util.Log;

import com.carlos.home.jihuanshe.JihuansheNetworkProbe;
import com.carlos.home.idlefish.IdlefishMtopProbe;
import com.carlos.home.idlefish.IdlefishWebViewJsProbe;
import com.lody.virtual.client.core.AppCallback;
import com.lody.virtual.client.core.VirtualCore;

public class IdlefishApp extends App {
    private static final String TAG = "IdlefishApp";
    private static final boolean ENABLE_IDLEFISH_MTOP_PROBE = false;
    private static final boolean ENABLE_IDLEFISH_JS_PROBE = true;

    @Override
    public void onCreate() {
        super.onCreate();
        AppCallback origin = getAppCallback();
        VirtualCore.get().setAppCallback(new IdlefishAppCallback(origin));
        Log.i(TAG, "IdlefishApp callback installed, probeVersion=" + IdlefishWebViewJsProbe.version());
    }

    private static final class IdlefishAppCallback implements AppCallback {
        private final AppCallback origin;

        private IdlefishAppCallback(AppCallback origin) {
            this.origin = origin == null ? AppCallback.EMPTY : origin;
        }

        @Override
        public void beforeStartApplication(String packageName, String processName, Context context) {
            origin.beforeStartApplication(packageName, processName, context);
        }

        @Override
        public void beforeApplicationCreate(String packageName, String processName, Application application) {
            origin.beforeApplicationCreate(packageName, processName, application);
        }

        @Override
        public void afterApplicationCreate(String packageName, String processName, Application application) {
            origin.afterApplicationCreate(packageName, processName, application);
            if (ENABLE_IDLEFISH_MTOP_PROBE && "com.taobao.idlefish".equals(packageName)) {
                try {
                    IdlefishMtopProbe.install(application.getClassLoader(), application, packageName, processName);
                } catch (Throwable throwable) {
                    Log.e(TAG, "install IdlefishMtopProbe failed", throwable);
                }
            }
            if (ENABLE_IDLEFISH_JS_PROBE && "com.taobao.idlefish".equals(packageName)) {
                Log.i(TAG, "Idlefish JS probe enabled, package=" + packageName
                        + ", process=" + processName
                        + ", probeVersion=" + IdlefishWebViewJsProbe.version());
            }
            if ("com.jihuanshe".equals(packageName)) {
                try {
                    JihuansheNetworkProbe.install(application.getClassLoader(), application, packageName, processName);
                } catch (Throwable throwable) {
                    Log.e(TAG, "install JihuansheNetworkProbe failed", throwable);
                }
            }
        }

        @Override
        public void beforeActivityOnCreate(Activity activity) {
            origin.beforeActivityOnCreate(activity);
        }

        @Override
        public void afterActivityOnCreate(Activity activity) {
            origin.afterActivityOnCreate(activity);
        }

        @Override
        public void beforeActivityOnStart(Activity activity) {
            origin.beforeActivityOnStart(activity);
        }

        @Override
        public void afterActivityOnStart(Activity activity) {
            origin.afterActivityOnStart(activity);
        }

        @Override
        public void beforeActivityOnResume(Activity activity) {
            origin.beforeActivityOnResume(activity);
        }

        @Override
        public void afterActivityOnResume(Activity activity) {
            origin.afterActivityOnResume(activity);
            if (ENABLE_IDLEFISH_JS_PROBE) {
                try {
                    IdlefishWebViewJsProbe.onActivityResumed(activity);
                } catch (Throwable throwable) {
                    Log.e(TAG, "IdlefishWebViewJsProbe failed", throwable);
                }
            }
        }

        @Override
        public void beforeActivityOnStop(Activity activity) {
            origin.beforeActivityOnStop(activity);
        }

        @Override
        public void afterActivityOnStop(Activity activity) {
            origin.afterActivityOnStop(activity);
        }

        @Override
        public void beforeActivityOnDestroy(Activity activity) {
            origin.beforeActivityOnDestroy(activity);
        }

        @Override
        public void afterActivityOnDestroy(Activity activity) {
            origin.afterActivityOnDestroy(activity);
        }
    }
}
