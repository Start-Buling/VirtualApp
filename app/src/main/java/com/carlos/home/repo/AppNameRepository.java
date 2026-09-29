package com.carlos.home.repo;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public final class AppNameRepository {
    private static final String PREF_NAME = "virtual_app_names";
    private static final String KEY_PREFIX = "name:";

    private AppNameRepository() {
    }

    public static String getDisplayName(Context context, String packageName, int userId, String defaultName) {
        String customName = getCustomName(context, packageName, userId);
        return TextUtils.isEmpty(customName) ? defaultName : customName;
    }

    public static String getCustomName(Context context, String packageName, int userId) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return null;
        }
        return getPrefs(context).getString(buildKey(packageName, userId), null);
    }

    public static void setCustomName(Context context, String packageName, int userId, String name) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return;
        }
        String value = name == null ? null : name.trim();
        SharedPreferences.Editor editor = getPrefs(context).edit();
        if (TextUtils.isEmpty(value)) {
            editor.remove(buildKey(packageName, userId));
        } else {
            editor.putString(buildKey(packageName, userId), value);
        }
        editor.apply();
    }

    public static void clearCustomName(Context context, String packageName, int userId) {
        setCustomName(context, packageName, userId, null);
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    private static String buildKey(String packageName, int userId) {
        return KEY_PREFIX + userId + ":" + packageName;
    }
}
