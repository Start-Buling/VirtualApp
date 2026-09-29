package com.carlos.home.idlefish;

import android.content.Context;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class IdlefishEventSyncer {
    private static final String TAG = "IdlefishEventSyncer";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private IdlefishEventSyncer() {
    }

    public static void enqueue(Context context, String eventJson) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                IdlefishEventStore.enqueue(appContext, eventJson);
            } catch (Throwable throwable) {
                Log.e(TAG, "enqueue failed", throwable);
            }
        });
    }

    public static void enqueueAndUploadIfEnabled(Context context, String eventJson) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                IdlefishEventStore.enqueue(appContext, eventJson);
                if (IdlefishSyncConfig.isEnabled(appContext)) {
                    IdlefishEventUploader.UploadResult result = IdlefishEventUploader.uploadPending(appContext);
                    Log.i(TAG, "upload result success=" + result.success
                            + ", skipped=" + result.skipped
                            + ", count=" + result.uploadedCount
                            + ", message=" + result.message);
                }
            } catch (Throwable throwable) {
                Log.e(TAG, "enqueueAndUploadIfEnabled failed", throwable);
            }
        });
    }

    public static void uploadDiscoveryOrEnqueueIfEnabled(Context context, String eventJson) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                if (!IdlefishSyncConfig.isEnabled(appContext)) {
                    IdlefishEventStore.enqueue(appContext, eventJson);
                    return;
                }
                String endpoint = IdlefishSyncConfig.getDiscoveryEndpoint(appContext);
                if (endpoint.length() == 0) {
                    IdlefishEventStore.enqueue(appContext, eventJson);
                    IdlefishEventUploader.UploadResult result = IdlefishEventUploader.uploadPending(appContext);
                    Log.i(TAG, "discovery fallback upload result success=" + result.success
                            + ", skipped=" + result.skipped
                            + ", count=" + result.uploadedCount
                            + ", message=" + result.message);
                    return;
                }
                IdlefishEventUploader.UploadResult result = IdlefishEventUploader.uploadSingleEvent(
                        appContext,
                        endpoint,
                        IdlefishSyncConfig.getDeviceSecret(appContext),
                        eventJson);
                Log.i(TAG, "discovery upload result success=" + result.success
                        + ", skipped=" + result.skipped
                        + ", count=" + result.uploadedCount
                        + ", message=" + result.message);
                if (!result.success) {
                    IdlefishEventStore.enqueue(appContext, eventJson);
                }
            } catch (Throwable throwable) {
                Log.e(TAG, "uploadDiscoveryOrEnqueueIfEnabled failed", throwable);
            }
        });
    }

    public static void uploadPendingIfEnabled(Context context) {
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            if (!IdlefishSyncConfig.isEnabled(appContext)) {
                return;
            }
            IdlefishEventUploader.UploadResult result = IdlefishEventUploader.uploadPending(appContext);
            Log.i(TAG, "upload result success=" + result.success
                    + ", skipped=" + result.skipped
                    + ", count=" + result.uploadedCount
                    + ", message=" + result.message);
        });
    }
}
