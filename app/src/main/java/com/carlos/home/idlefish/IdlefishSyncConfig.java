package com.carlos.home.idlefish;

import android.content.Context;
import android.content.SharedPreferences;

public final class IdlefishSyncConfig {
    private static final String PREF_NAME = "idlefish_sync_config";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_DISCOVERY_ENDPOINT = "discovery_endpoint";
    private static final String KEY_DEVICE_SECRET = "device_secret";
    private static final String KEY_DEVICE_NO = "device_no";

    private IdlefishSyncConfig() {
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static String getEndpoint(Context context) {
        return prefs(context).getString(KEY_ENDPOINT, "");
    }

    public static void setEndpoint(Context context, String endpoint) {
        prefs(context).edit().putString(KEY_ENDPOINT, endpoint == null ? "" : endpoint.trim()).apply();
    }

    public static String getDiscoveryEndpoint(Context context) {
        return prefs(context).getString(KEY_DISCOVERY_ENDPOINT, "");
    }

    public static void setDiscoveryEndpoint(Context context, String endpoint) {
        prefs(context).edit().putString(KEY_DISCOVERY_ENDPOINT, endpoint == null ? "" : endpoint.trim()).apply();
    }

    public static String getDeviceSecret(Context context) {
        return prefs(context).getString(KEY_DEVICE_SECRET, "");
    }

    public static void setDeviceSecret(Context context, String deviceSecret) {
        prefs(context).edit().putString(KEY_DEVICE_SECRET, deviceSecret == null ? "" : deviceSecret).apply();
    }

    public static String getDeviceNo(Context context) {
        return prefs(context).getString(KEY_DEVICE_NO, "");
    }

    public static void setDeviceNo(Context context, String deviceNo) {
        prefs(context).edit().putString(KEY_DEVICE_NO, deviceNo == null ? "" : deviceNo.trim()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
}
