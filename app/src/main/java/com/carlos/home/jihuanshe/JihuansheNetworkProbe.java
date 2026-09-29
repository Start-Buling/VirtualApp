package com.carlos.home.jihuanshe;

import android.app.Application;
import android.os.Process;
import android.util.Log;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public final class JihuansheNetworkProbe {
    private static final String TAG = "JihuansheProbe";
    private static final String TARGET_PACKAGE = "com.jihuanshe";
    private static final int MAX_INSTALL_ATTEMPTS = 40;
    private static final long INSTALL_RETRY_DELAY_MS = 1000L;
    private static final int MAX_VALUE_LENGTH = 1800;
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "(?i)(cookie|session|token|auth|authorization|access|refresh|secret|password|passwd|pwd|sign|signature|csrf|xsrf|ticket|credential|sid|bearer)");
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<>());
    private static volatile boolean installed;

    private JihuansheNetworkProbe() {
    }

    public static void install(ClassLoader classLoader, Application application, String packageName, String processName) {
        if (installed || classLoader == null || application == null || !TARGET_PACKAGE.equals(packageName)) {
            return;
        }
        installed = true;
        Log.i(TAG, "schedule install package=" + packageName + ", process=" + processName);
        new Thread(() -> installLoop(classLoader, packageName, processName), "JihuansheNetworkProbe").start();
    }

    private static void installLoop(ClassLoader classLoader, String packageName, String processName) {
        preloadSandHook();
        installBootClassHooks();
        for (int attempt = 1; attempt <= MAX_INSTALL_ATTEMPTS; attempt++) {
            int hookCount = 0;
            hookCount += installOkHttpHooks(classLoader);
            hookCount += installCronetHooks(classLoader);
            hookCount += installFlutterHooks(classLoader);
            if (hookCount > 0) {
                Log.i(TAG, "dynamic hooks installed count=" + hookCount
                        + ", package=" + packageName + ", process=" + processName + ", attempt=" + attempt);
                return;
            }
            if (attempt == 1 || attempt % 5 == 0) {
                Log.i(TAG, "waiting network classes attempt=" + attempt);
            }
            sleepQuietly(INSTALL_RETRY_DELAY_MS);
        }
        Log.w(TAG, "no app network classes found after retries; boot hooks remain active");
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

    private static void installBootClassHooks() {
        hookUrlOpenConnection();
        hookHttpURLConnection();
        hookSocketConnect();
    }

    private static void hookUrlOpenConnection() {
        Class<?> urlClass = URL.class;
        hookAll(urlClass, "openConnection", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                logOnce("url.openConnection " + safe(param.thisObject));
            }
        });
    }

    private static void hookHttpURLConnection() {
        hookAll(HttpURLConnection.class, "setRequestMethod", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                logOnce("http.method " + safe(param.args != null && param.args.length > 0 ? param.args[0] : ""));
            }
        });
        hookAll(HttpURLConnection.class, "setRequestProperty", new HeaderHook("http.setHeader"));
        hookAll(HttpURLConnection.class, "addRequestProperty", new HeaderHook("http.addHeader"));
        hookAll(HttpURLConnection.class, "getInputStream", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                logOnce("http.getInputStream " + connectionUrl(param.thisObject));
            }
        });
        hookAll(HttpURLConnection.class, "getOutputStream", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                logOnce("http.getOutputStream " + connectionUrl(param.thisObject));
            }
        });
    }

    private static void hookSocketConnect() {
        Class<?> socketClass = XposedHelpers.findClassIfExists("java.net.Socket", null);
        if (socketClass == null) {
            return;
        }
        hookAll(socketClass, "connect", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length == 0) {
                    return;
                }
                SocketAddress address = (SocketAddress) param.args[0];
                if (address instanceof InetSocketAddress) {
                    InetSocketAddress inet = (InetSocketAddress) address;
                    logOnce("socket.connect " + inet.getHostString() + ":" + inet.getPort());
                } else {
                    logOnce("socket.connect " + safe(address));
                }
            }
        });
    }

    private static int installOkHttpHooks(ClassLoader classLoader) {
        int hookCount = 0;
        Class<?> requestBuilder = XposedHelpers.findClassIfExists("okhttp3.Request$Builder", classLoader);
        if (requestBuilder != null) {
            hookCount += hookAll(requestBuilder, "url", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    logOnce("okhttp.url " + safe(firstArg(param)));
                }
            });
            hookCount += hookAll(requestBuilder, "header", new HeaderHook("okhttp.header"));
            hookCount += hookAll(requestBuilder, "addHeader", new HeaderHook("okhttp.addHeader"));
            hookCount += hookAll(requestBuilder, "method", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    logOnce("okhttp.method " + safe(firstArg(param)));
                }
            });
        }

        Class<?> realCall = XposedHelpers.findClassIfExists("okhttp3.RealCall", classLoader);
        if (realCall != null) {
            XC_MethodHook callHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    logOnce("okhttp.call " + safe(invokeNoArg(param.thisObject, "request")));
                }
            };
            hookCount += hookAll(realCall, "execute", callHook);
            hookCount += hookAll(realCall, "enqueue", callHook);
        }

        Class<?> responseBody = XposedHelpers.findClassIfExists("okhttp3.ResponseBody", classLoader);
        if (responseBody != null) {
            hookCount += hookAll(responseBody, "string", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    String text = result == null ? "" : String.valueOf(result);
                    if (isInteresting(text)) {
                        logLarge("okhttp.response", redactJsonLike(text));
                    }
                }
            });
        }
        return hookCount;
    }

    private static int installCronetHooks(ClassLoader classLoader) {
        int hookCount = 0;
        Class<?> builder = XposedHelpers.findClassIfExists("org.chromium.net.UrlRequest$Builder", classLoader);
        if (builder == null) {
            return 0;
        }
        hookCount += hookAll(builder, "setHttpMethod", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                logOnce("cronet.method " + safe(firstArg(param)));
            }
        });
        hookCount += hookAll(builder, "addHeader", new HeaderHook("cronet.addHeader"));
        hookCount += hookAll(builder, "build", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                logOnce("cronet.build " + safe(param.thisObject));
            }
        });
        return hookCount;
    }

    private static int installFlutterHooks(ClassLoader classLoader) {
        int hookCount = 0;
        Class<?> methodChannel = XposedHelpers.findClassIfExists("io.flutter.plugin.common.MethodChannel", classLoader);
        if (methodChannel != null) {
            hookCount += hookAll(methodChannel, "invokeMethod", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String method = safe(firstArg(param));
                    Object arg = param.args != null && param.args.length > 1 ? param.args[1] : null;
                    if (isInteresting(method) || isInteresting(String.valueOf(arg))) {
                        logLarge("flutter.invokeMethod " + method, safe(arg));
                    }
                }
            });
        }
        return hookCount;
    }

    private static int hookAll(Class<?> clazz, String methodName, XC_MethodHook hook) {
        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(clazz, methodName, hook);
            int count = hooks == null ? 0 : hooks.size();
            if (count > 0) {
                Log.i(TAG, "hooked " + clazz.getName() + "#" + methodName + " count=" + count);
            }
            return count;
        } catch (Throwable throwable) {
            Log.w(TAG, "hook failed " + clazz.getName() + "#" + methodName + ": " + throwable);
            return 0;
        }
    }

    private static final class HeaderHook extends XC_MethodHook {
        private final String prefix;

        private HeaderHook(String prefix) {
            this.prefix = prefix;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            Object key = param.args != null && param.args.length > 0 ? param.args[0] : "";
            Object value = param.args != null && param.args.length > 1 ? param.args[1] : "";
            logOnce(prefix + " " + safe(key) + "=" + safeHeader(key, value));
        }
    }

    private static Object firstArg(XC_MethodHook.MethodHookParam param) {
        return param.args != null && param.args.length > 0 ? param.args[0] : "";
    }

    private static String connectionUrl(Object connection) {
        Object url = invokeNoArg(connection, "getURL");
        return safe(url);
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) {
            return "";
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(target);
            return value == null ? "" : value;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static boolean isInteresting(String value) {
        if (value == null || value.length() == 0) {
            return false;
        }
        String lower = value.toLowerCase(Locale.US);
        return lower.contains("jihuanshe")
                || lower.contains("card")
                || lower.contains("price")
                || lower.contains("market")
                || lower.contains("seller")
                || lower.contains("product")
                || value.contains(text(0x76AE, 0x5361, 0x4E18))
                || value.contains(text(0x5361, 0x724C))
                || value.contains(text(0x552E, 0x4EF7))
                || value.contains(text(0x5B9D, 0x53EF, 0x68A6));
    }

    private static void logOnce(String message) {
        String safeMessage = safe(message);
        if (safeMessage.length() == 0) {
            return;
        }
        String key = safeMessage.length() > 240 ? safeMessage.substring(0, 240) : safeMessage;
        if (SEEN.add(key)) {
            Log.i(TAG, safeMessage);
        }
    }

    private static void logLarge(String prefix, String value) {
        String safeValue = safe(value);
        int length = safeValue.length();
        if (length <= MAX_VALUE_LENGTH) {
            logOnce(prefix + " " + safeValue);
            return;
        }
        Log.i(TAG, prefix + " length=" + length + " head=" + safeValue.substring(0, MAX_VALUE_LENGTH));
    }

    private static String safeHeader(Object key, Object value) {
        String name = key == null ? "" : String.valueOf(key);
        if (SENSITIVE_KEY_PATTERN.matcher(name).find()) {
            return "[redacted]";
        }
        return safe(value);
    }

    private static String safe(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        text = redactJsonLike(text);
        if (text.length() > MAX_VALUE_LENGTH) {
            return text.substring(0, MAX_VALUE_LENGTH) + "...";
        }
        return text;
    }

    private static String redactJsonLike(String text) {
        if (text == null) {
            return "";
        }
        String result = text;
        result = result.replaceAll("(?i)(authorization|access_token|refresh_token|token|cookie|sign|signature|password|passwd|pwd)(\"?\\s*[:=]\\s*\"?)[^\",&\\s}]+", "$1$2[redacted]");
        result = result.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [redacted]");
        return result;
    }

    private static String text(int... codePoints) {
        StringBuilder builder = new StringBuilder();
        for (int codePoint : codePoints) {
            builder.appendCodePoint(codePoint);
        }
        return builder.toString();
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
