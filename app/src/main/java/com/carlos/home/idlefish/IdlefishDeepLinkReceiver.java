package com.carlos.home.idlefish;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.remote.InstalledAppInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class IdlefishDeepLinkReceiver extends BroadcastReceiver {
    public static final String ACTION_OPEN_URL = "com.carlos.multiapp.IDLEFISH_OPEN_URL";
    public static final String ACTION_LIST_VIRTUAL_USERS = "com.carlos.multiapp.IDLEFISH_LIST_VIRTUAL_USERS";
    public static final String EXTRA_PACKAGE_NAME = "package_name";
    public static final String EXTRA_USER_ID = "user_id";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_INTENT_ACTION = "intent_action";
    public static final String EXTRA_COMPONENT_NAME = "component_name";
    public static final String EXTRA_SYNC_ENABLED = "sync_enabled";
    public static final String EXTRA_SYNC_ENDPOINT = "sync_endpoint";
    public static final String EXTRA_DISCOVERY_ENDPOINT = "discovery_endpoint";
    public static final String EXTRA_DEVICE_SECRET = "device_secret";
    public static final String EXTRA_DEVICE_NO = "device_no";

    private static final String TAG = "IdlefishDeepLink";
    private static final String DEFAULT_PACKAGE_NAME = "com.taobao.idlefish";
    private static final String DEFAULT_SERVICE_POINTS_URL =
            "https://h5.m.goofish.com/wow/moyu/moyu-project/fish-shop-data/pages/service-points?spm=a2170.28358589.0.0&isOldFriendly=false&_from__=webhybrid";

    @Override
    public void onReceive(Context context, Intent broadcast) {
        String action = broadcast.getAction();
        if (ACTION_LIST_VIRTUAL_USERS.equals(action)) {
            writeVirtualUsersSnapshot(context, broadcast);
            return;
        }
        if (!ACTION_OPEN_URL.equals(action)) {
            return;
        }

        applySyncConfig(context, broadcast);

        String packageName = broadcast.getStringExtra(EXTRA_PACKAGE_NAME);
        if (TextUtils.isEmpty(packageName)) {
            packageName = DEFAULT_PACKAGE_NAME;
        }

        String url = broadcast.getStringExtra(EXTRA_URL);
        if (TextUtils.isEmpty(url)) {
            url = DEFAULT_SERVICE_POINTS_URL;
        }

        int userId = broadcast.getIntExtra(EXTRA_USER_ID, 0);
        String intentAction = broadcast.getStringExtra(EXTRA_INTENT_ACTION);
        if (TextUtils.isEmpty(intentAction)) {
            intentAction = Intent.ACTION_VIEW;
        }

        Intent viewIntent = new Intent(intentAction, Uri.parse(url));
        viewIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        String componentName = broadcast.getStringExtra(EXTRA_COMPONENT_NAME);
        if (TextUtils.isEmpty(componentName)) {
            viewIntent.setPackage(packageName);
        } else {
            viewIntent.setClassName(packageName, componentName);
        }
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        int result = VActivityManager.get().startActivity(viewIntent, userId);
        String message = "open url user=" + userId + " result=" + result;
        Log.i(TAG, message + ", action=" + intentAction + ", package=" + packageName
                + ", component=" + componentName
                + ", probeVersion=" + IdlefishWebViewJsProbe.version()
                + ", syncEnabled=" + IdlefishSyncConfig.isEnabled(context)
                + ", endpoint=" + IdlefishSyncConfig.getEndpoint(context)
                + ", discoveryEndpoint=" + IdlefishSyncConfig.getDiscoveryEndpoint(context)
                + ", url=" + url);
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private void writeVirtualUsersSnapshot(Context context, Intent broadcast) {
        String packageName = broadcast.getStringExtra(EXTRA_PACKAGE_NAME);
        if (TextUtils.isEmpty(packageName)) {
            packageName = DEFAULT_PACKAGE_NAME;
        }

        JSONObject snapshot = new JSONObject();
        JSONArray users = new JSONArray();
        try {
            InstalledAppInfo info = VirtualCore.get().getInstalledAppInfo(packageName, 0);
            if (info != null) {
                for (int userId : info.getInstalledUsers()) {
                    users.put(userId);
                }
            }

            snapshot.put("ok", true);
            snapshot.put("package_name", packageName);
            snapshot.put("virtual_user_ids", users);
            snapshot.put("captured_at", System.currentTimeMillis());
        } catch (Throwable throwable) {
            Log.e(TAG, "list virtual users failed", throwable);
            try {
                snapshot.put("ok", false);
                snapshot.put("package_name", packageName);
                snapshot.put("virtual_user_ids", users);
                snapshot.put("error", throwable.getMessage());
                snapshot.put("captured_at", System.currentTimeMillis());
            } catch (Throwable ignored) {
                // Keep the receiver best-effort; the Agent will fall back to config.
            }
        }

        File outputDir = context.getFilesDir();
        File output = new File(outputDir, "idlefish_virtual_users.json");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(output, false), StandardCharsets.UTF_8)) {
            writer.write(snapshot.toString());
            writer.write('\n');
            Log.i(TAG, "virtual users snapshot written: " + output.getAbsolutePath()
                    + ", users=" + users);
            setResultCode(0);
            setResultData(snapshot.toString());
        } catch (Throwable throwable) {
            Log.e(TAG, "write virtual users snapshot failed", throwable);
            setResultCode(1);
            setResultData(throwable.getMessage());
        }
    }

    private static void applySyncConfig(Context context, Intent broadcast) {
        String endpoint = broadcast.getStringExtra(EXTRA_SYNC_ENDPOINT);
        if (!TextUtils.isEmpty(endpoint)) {
            IdlefishSyncConfig.setEndpoint(context, endpoint);
            IdlefishSyncConfig.setEnabled(context, true);
            Log.i(TAG, "sync endpoint configured: " + endpoint);
        }
        if (broadcast.hasExtra(EXTRA_SYNC_ENABLED)) {
            boolean enabled = broadcast.getBooleanExtra(EXTRA_SYNC_ENABLED, false);
            IdlefishSyncConfig.setEnabled(context, enabled);
            Log.i(TAG, "sync enabled configured: " + enabled);
        }
        String discoveryEndpoint = broadcast.getStringExtra(EXTRA_DISCOVERY_ENDPOINT);
        if (!TextUtils.isEmpty(discoveryEndpoint)) {
            IdlefishSyncConfig.setDiscoveryEndpoint(context, discoveryEndpoint);
            IdlefishSyncConfig.setEnabled(context, true);
            Log.i(TAG, "discovery endpoint configured: " + discoveryEndpoint);
        }
        String deviceSecret = broadcast.getStringExtra(EXTRA_DEVICE_SECRET);
        if (!TextUtils.isEmpty(deviceSecret)) {
            IdlefishSyncConfig.setDeviceSecret(context, deviceSecret);
            Log.i(TAG, "device secret configured");
        }
        String deviceNo = broadcast.getStringExtra(EXTRA_DEVICE_NO);
        if (!TextUtils.isEmpty(deviceNo)) {
            IdlefishSyncConfig.setDeviceNo(context, deviceNo);
            Log.i(TAG, "device no configured: " + deviceNo);
        }
    }
}
