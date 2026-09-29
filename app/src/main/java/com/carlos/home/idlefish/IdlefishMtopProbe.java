package com.carlos.home.idlefish;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.os.VUserHandle;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class IdlefishMtopProbe {
    private static final String TAG = "IdlefishMtopProbe";
    private static final String TARGET_PACKAGE = "com.taobao.idlefish";
    private static final int MAX_INSTALL_ATTEMPTS = 20;
    private static final long INSTALL_RETRY_DELAY_MS = 1500L;
    private static final boolean ENABLE_RESPONSE_HOOK = false;
    private static final boolean ENABLE_REQUEST_HOOK = true;
    private static final Set<String> SEEN_HASHES = Collections.synchronizedSet(new HashSet<>());
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "(?i)(cookie|session|token|auth|authorization|access|refresh|secret|password|passwd|pwd|sign|signature|csrf|xsrf|ticket|credential|sid|ecode)");
    private static volatile boolean installed;

    private IdlefishMtopProbe() {
    }

    public static void install(ClassLoader classLoader, Application application, String packageName, String processName) {
        if (installed || !TARGET_PACKAGE.equals(packageName) || classLoader == null || application == null) {
            return;
        }
        installed = true;
        Log.i(TAG, "schedule install package=" + packageName + ", process=" + processName);
        new Thread(() -> installWhenMtopReady(classLoader, application, packageName, processName),
                "IdlefishMtopProbe").start();
    }

    private static void installWhenMtopReady(ClassLoader classLoader, Application application, String packageName, String processName) {
        for (int attempt = 1; attempt <= MAX_INSTALL_ATTEMPTS; attempt++) {
            if (hasMtopClass(classLoader)) {
                Log.i(TAG, "install package=" + packageName + ", process=" + processName + ", attempt=" + attempt);
                preloadSandHook();
                if (ENABLE_RESPONSE_HOOK) {
                    hookMtopResponse(classLoader, application, packageName, processName);
                } else {
                    Log.i(TAG, "MtopResponse hook disabled for stability");
                }
                if (ENABLE_REQUEST_HOOK) {
                    hookMtopRequest(classLoader);
                }
                return;
            }
            Log.i(TAG, "mtop classes not ready, attempt=" + attempt);
            try {
                Thread.sleep(INSTALL_RETRY_DELAY_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.w(TAG, "mtop classes not found after retries");
    }

    private static void preloadSandHook() {
        try {
            String library = Process.is64Bit() ? "sandhook_64" : "sandhook";
            System.loadLibrary(library);
            Log.i(TAG, "preloaded " + library);
        } catch (Throwable throwable) {
            Log.e(TAG, "preload SandHook failed", throwable);
        }
    }

    private static boolean hasMtopClass(ClassLoader classLoader) {
        return XposedHelpers.findClassIfExists("mtopsdk.mtop.domain.MtopResponse", classLoader) != null
                || XposedHelpers.findClassIfExists("mtopsdk.mtop.domain.MtopRequest", classLoader) != null
                || XposedHelpers.findClassIfExists("mtopsdk.mtop.intf.Mtop", classLoader) != null;
    }

    private static void hookMtopResponse(ClassLoader classLoader, Application application, String packageName, String processName) {
        Class<?> responseClass = XposedHelpers.findClassIfExists("mtopsdk.mtop.domain.MtopResponse", classLoader);
        if (responseClass == null) {
            Log.w(TAG, "MtopResponse not found");
            return;
        }

        XC_MethodHook responseHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object result = param.getResult();
                String body = bodyFromResult(result);
                if (body == null || !isInteresting(body)) {
                    return;
                }
                capture(application, packageName, processName, param.thisObject, param.method.getName(), body);
            }
        };

        int hookCount = 0;
        hookCount += hookAll(responseClass, "getBytedata", responseHook);
        hookCount += hookAll(responseClass, "getDataJsonObject", responseHook);
        hookCount += hookAll(responseClass, "getDataJsonObjectOrigin", responseHook);
        hookCount += hookAll(responseClass, "toString", responseHook);
        Log.i(TAG, "MtopResponse hook count=" + hookCount);
    }

    private static void hookMtopRequest(ClassLoader classLoader) {
        Class<?> requestClass = XposedHelpers.findClassIfExists("mtopsdk.mtop.domain.MtopRequest", classLoader);
        if (requestClass == null) {
            Log.w(TAG, "MtopRequest not found");
            return;
        }
        XC_MethodHook requestHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Object value = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (value != null && !SENSITIVE_KEY_PATTERN.matcher(param.method.getName()).find()) {
                    Log.i(TAG, "request " + param.method.getName() + "=" + safeShort(value));
                }
            }
        };
        int hookCount = 0;
        hookCount += hookAll(requestClass, "setApiName", requestHook);
        hookCount += hookAll(requestClass, "setVersion", requestHook);
        Log.i(TAG, "MtopRequest hook count=" + hookCount);
    }

    private static int hookAll(Class<?> clazz, String methodName, XC_MethodHook hook) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(clazz, methodName, hook);
            return hooks == null ? 0 : hooks.size();
        } catch (Throwable throwable) {
            Log.w(TAG, "hook failed " + clazz.getName() + "#" + methodName + ": " + throwable);
            return 0;
        }
    }

    private static String bodyFromResult(Object result) {
        if (result == null) {
            return null;
        }
        if (result instanceof byte[]) {
            return new String((byte[]) result);
        }
        return String.valueOf(result);
    }

    private static boolean isInteresting(String body) {
        if (body.length() < 20) {
            return false;
        }
        String lower = body.toLowerCase(Locale.US);
        return lower.contains("service")
                || lower.contains("score")
                || lower.contains("shop")
                || lower.contains("seller")
                || lower.contains("user")
                || lower.contains("moyu")
                || lower.contains("workbench")
                || lower.contains("service-points")
                || body.contains(text(0x5C0F, 0x94FA))
                || body.contains(text(0x670D, 0x52A1, 0x5206))
                || body.contains(text(0x5E97, 0x94FA))
                || body.contains(text(0x5356, 0x5BB6));
    }

    private static void capture(Application application, String packageName, String processName, Object response, String method, String body) {
        try {
            String hash = sha256(body);
            if (!SEEN_HASHES.add(hash)) {
                return;
            }
            Log.i(TAG, "candidate method=" + method
                    + ", api=" + readResponseString(response, "api", "getApi")
                    + ", hash=" + hash
                    + ", keywords=" + matchedKeywords(body));

            Context hostContext = VirtualCore.get().getContext();
            IdlefishEventSyncer.enqueueAndUploadIfEnabled(hostContext, buildCandidateEvent(
                    packageName,
                    processName,
                    method,
                    response,
                    body,
                    hash
            ).toString());
        } catch (Throwable throwable) {
            Log.e(TAG, "capture failed", throwable);
        }
    }

    private static JSONObject buildCandidateEvent(
            String packageName,
            String processName,
            String method,
            Object response,
            String body,
            String hash
    ) throws Exception {
        int virtualUserId = VUserHandle.myUserId();
        String deviceNo = IdlefishSyncConfig.getDeviceNo(VirtualCore.get().getContext());

        JSONObject account = new JSONObject();
        account.put("device_no", deviceNo.length() == 0 ? JSONObject.NULL : deviceNo);
        account.put("device_id", Build.SERIAL);
        account.put("device_serial", Build.SERIAL);
        account.put("collector_type", "virtualapp_mtop_probe");
        account.put("app_slot", virtualUserId + 1);
        account.put("package_name", packageName);
        account.put("virtual_user_id", virtualUserId);
        account.put("shop_id", JSONObject.NULL);
        account.put("account_alias", "mtop-probe");

        JSONObject page = new JSONObject();
        page.put("activity", processName == null ? "" : processName);
        page.put("url", "mtop://candidate");

        JSONObject raw = new JSONObject();
        raw.put("api", readResponseString(response, "api", "getApi"));
        raw.put("v", readResponseString(response, "v", "getV"));
        raw.put("ret_code", readResponseString(response, "retCode", "getRetCode"));
        raw.put("source_method", method);
        raw.put("payload_hash", hash);
        raw.put("payload_length", body.length());
        raw.put("matched_keywords", matchedKeywords(body));
        raw.put("identity_fields", extractIdentityFields(body));

        JSONObject event = new JSONObject();
        event.put("event_type", "idlefish_mtop_candidate");
        event.put("schema_version", 1);
        event.put("event_id", UUID.randomUUID().toString());
        event.put("captured_at", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
        event.put("source", "android_mtop_probe");
        event.put("device_no", account.opt("device_no"));
        event.put("device_id", account.optString("device_id", ""));
        event.put("collector_type", account.optString("collector_type", ""));
        event.put("app_slot", account.optInt("app_slot"));
        event.put("virtual_user_id", account.optInt("virtual_user_id"));
        event.put("account", account);
        event.put("page", page);
        event.put("metrics", new JSONObject());
        event.put("raw", raw);
        return event;
    }

    private static String readResponseString(Object target, String fieldName, String methodName) {
        String value = readString(target, fieldName);
        if (value.length() > 0) {
            return value;
        }
        return readNoArgString(target, methodName);
    }

    private static String readString(Object target, String fieldName) {
        if (target == null) {
            return "";
        }
        try {
            Field field = findField(target.getClass(), fieldName);
            if (field == null) {
                return "";
            }
            field.setAccessible(true);
            Object value = field.get(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String readNoArgString(Object target, String methodName) {
        if (target == null) {
            return "";
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static JSONObject extractIdentityFields(String body) {
        JSONObject fields = new JSONObject();
        try {
            Object parsed = new JSONTokener(body).nextValue();
            collectIdentityFields(parsed, fields, 0);
        } catch (Throwable ignored) {
            // Non-JSON responses are still useful through hash/api/keyword metadata.
        }
        return fields;
    }

    private static void collectIdentityFields(Object value, JSONObject out, int depth) throws Exception {
        if (value == null || depth > 5 || out.length() >= 40) {
            return;
        }
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            int scanned = 0;
            while (keys.hasNext() && scanned < 100 && out.length() < 40) {
                scanned++;
                String key = keys.next();
                if (SENSITIVE_KEY_PATTERN.matcher(key).find()) {
                    continue;
                }
                Object child = object.opt(key);
                if (isIdentityKey(key) && (child instanceof String || child instanceof Number || child instanceof Boolean)) {
                    out.put(key, safeShort(child));
                }
                collectIdentityFields(child, out, depth + 1);
            }
            return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            int max = Math.min(array.length(), 100);
            for (int i = 0; i < max && out.length() < 40; i++) {
                collectIdentityFields(array.opt(i), out, depth + 1);
            }
        }
    }

    private static boolean isIdentityKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.replace("_", "").replace("-", "").toLowerCase(Locale.US);
        return normalized.equals("userid")
                || normalized.equals("sellerid")
                || normalized.equals("selleruserid")
                || normalized.equals("shopid")
                || normalized.equals("shopno")
                || normalized.equals("shopuserid")
                || normalized.equals("ownerid")
                || normalized.equals("accountid")
                || normalized.equals("xyuserid")
                || normalized.equals("xianyuuserid")
                || normalized.equals("fishuserid")
                || normalized.equals("targetuserid");
    }

    private static JSONArray matchedKeywords(String body) {
        JSONArray keywords = new JSONArray();
        addKeywordIfMatched(keywords, body, "service");
        addKeywordIfMatched(keywords, body, "score");
        addKeywordIfMatched(keywords, body, "shop");
        addKeywordIfMatched(keywords, body, "seller");
        addKeywordIfMatched(keywords, body, "user");
        addKeywordIfMatched(keywords, body, "moyu");
        addKeywordIfMatched(keywords, body, "workbench");
        addKeywordIfMatched(keywords, body, "service-points");
        addKeywordIfMatched(keywords, body, text(0x5C0F, 0x94FA));
        addKeywordIfMatched(keywords, body, text(0x670D, 0x52A1, 0x5206));
        addKeywordIfMatched(keywords, body, text(0x5E97, 0x94FA));
        addKeywordIfMatched(keywords, body, text(0x5356, 0x5BB6));
        return keywords;
    }

    private static void addKeywordIfMatched(JSONArray keywords, String body, String keyword) {
        String lowerBody = body.toLowerCase(Locale.US);
        String lowerKeyword = keyword.toLowerCase(Locale.US);
        if (lowerBody.contains(lowerKeyword)) {
            keywords.put(keyword);
        }
    }

    private static String safeShort(Object value) {
        String text = String.valueOf(value);
        if (SENSITIVE_KEY_PATTERN.matcher(text).find()) {
            return "[redacted]";
        }
        return text.length() > 256 ? text.substring(0, 256) : text;
    }

    private static String text(int... codePoints) {
        StringBuilder builder = new StringBuilder();
        for (int codePoint : codePoints) {
            builder.appendCodePoint(codePoint);
        }
        return builder.toString();
    }

    private static String sha256(String body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(body.getBytes("UTF-8"));
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return builder.toString();
    }
}
