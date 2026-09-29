package com.carlos.home.idlefish;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.os.VUserHandle;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IdlefishWebViewJsProbe {
    private static final String TAG = "IdlefishJsProbe";
    private static final String PROBE_VERSION = "idlefish-probe-2026-05-21.1";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<String, Long> PROBED_AT = new HashMap<>();
    private static final Map<String, Long> UPLOADED_AT = new HashMap<>();
    private static final long PROBE_RESCHEDULE_MIN_MS = 1_000L;
    private static final long UPLOAD_DEDUP_MS = 30_000L;
    private static final String TARGET_PACKAGE = "com.taobao.idlefish";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^\\d+(?:\\.\\d+)?$");
    private static final Pattern PERCENT_PATTERN = Pattern.compile("^(\\d+)%$");
    private static final Pattern SENSITIVE_URL_KEY_PATTERN = Pattern.compile(
            "(?i)(cookie|session|token|auth|authorization|access|refresh|secret|password|passwd|pwd|sign|signature|csrf|xsrf|ticket|credential)");
    private static final Set<String> URL_ID_PARAM_KEYS = new HashSet<>();

    static {
        String[] keys = new String[]{
                "userId", "userid", "user_id",
                "sellerId", "sellerid", "seller_id", "sellerUserId", "seller_user_id",
                "shopId", "shopid", "shop_id", "shopNo", "shop_no",
                "shopUserId", "shop_user_id", "ownerId", "owner_id",
                "accountId", "account_id",
                "xyUserId", "xy_user_id", "xianyuUserId", "xianyu_user_id",
                "fishUserId", "fish_user_id", "targetUserId", "target_user_id"
        };
        for (String key : keys) {
            URL_ID_PARAM_KEYS.add(key.toLowerCase(Locale.US));
        }
    }

    private IdlefishWebViewJsProbe() {
    }

    public static String version() {
        return PROBE_VERSION;
    }

    public static void onActivityResumed(Activity activity) {
        if (activity == null || activity.getPackageName() == null
                || !TARGET_PACKAGE.equals(activity.getPackageName())) {
            return;
        }
        String activityName = activity.getClass().getName();
        if (!activityName.contains("WebHybridActivity")) {
            return;
        }
        String key = activityName + "@" + System.identityHashCode(activity);
        long now = System.currentTimeMillis();
        synchronized (PROBED_AT) {
            Long lastProbeAt = PROBED_AT.get(key);
            if (lastProbeAt != null && now - lastProbeAt < PROBE_RESCHEDULE_MIN_MS) {
                return;
            }
            PROBED_AT.put(key, now);
        }
        Log.i(TAG, "schedule probe version=" + PROBE_VERSION
                + ", activity=" + activityName
                + ", user=" + VUserHandle.myUserId()
                + ", key=" + key);
        for (int delayMs : new int[]{250, 750, 1500, 2500, 5000, 9000, 15000, 22000, 30000}) {
            MAIN.postDelayed(() -> probe(activity, key), delayMs);
        }
    }

    private static void probe(Activity activity, String probeKey) {
        try {
            View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
            View webView = findWebView(root);
            if (webView == null) {
                Log.i(TAG, "no WebView found activity=" + activity.getClass().getName());
                return;
            }
            Method evaluateJavascript = webView.getClass().getMethod(
                    "evaluateJavascript", String.class, ValueCallback.class);
            String script = "(function(){try{"
                    + "var w=window;"
                    + "var idKeys={userid:1,user_id:1,userId:1,sellerid:1,seller_id:1,sellerId:1,sellerUserId:1,seller_user_id:1,shopid:1,shop_id:1,shopId:1,shopNo:1,shop_no:1,shopUserId:1,shop_user_id:1,ownerId:1,owner_id:1,accountId:1,account_id:1,xyUserId:1,xy_user_id:1,xianyuUserId:1,xianyu_user_id:1,fishUserId:1,fish_user_id:1,targetUserId:1,target_user_id:1};"
                    + "var sensitive=/(cookie|session|token|auth|authorization|access|refresh|secret|password|passwd|pwd|sign|signature|csrf|xsrf|ticket|credential)/i;"
                    + "function safeString(v){try{return String(v==null?'':v).slice(0,256);}catch(e){return '';}}"
                    + "function baseUrl(u){u=safeString(u);var q=u.indexOf('?');var h=u.indexOf('#');var end=u.length;if(q>=0&&q<end)end=q;if(h>=0&&h<end)end=h;return u.slice(0,end);}"
                    + "function putParam(out,k,v){if(!k||!v||sensitive.test(k))return;var lk=String(k).toLowerCase();for(var name in idKeys){if(name.toLowerCase()===lk){out[k]=safeString(v);return;}}}"
                    + "function parseParams(u){var out={};try{u=String(u||'');var parts=[];var qi=u.indexOf('?');if(qi>=0)parts.push(u.slice(qi+1).split('#')[0]);var hi=u.indexOf('#');if(hi>=0){var hash=u.slice(hi+1);var hq=hash.indexOf('?');if(hq>=0)parts.push(hash.slice(hq+1));}for(var p=0;p<parts.length;p++){var pairs=parts[p].split('&');for(var i=0;i<pairs.length;i++){var eq=pairs[i].indexOf('=');if(eq<=0)continue;putParam(out,decodeURIComponent(pairs[i].slice(0,eq).replace(/\\+/g,' ')),decodeURIComponent(pairs[i].slice(eq+1).replace(/\\+/g,' ')));}}}catch(e){}return out;}"
                    + "function collectIds(obj,out,depth){if(!obj||depth>4)return;try{if(Array.isArray(obj)){for(var i=0;i<obj.length&&i<80;i++)collectIds(obj[i],out,depth+1);return;}if(typeof obj!=='object')return;var n=0;for(var k in obj){if(!Object.prototype.hasOwnProperty.call(obj,k)||sensitive.test(k))continue;var v=obj[k];var lk=String(k).toLowerCase();for(var name in idKeys){if(name.toLowerCase()===lk&&(typeof v==='string'||typeof v==='number'))out[k]=safeString(v);}if(n++<80)collectIds(v,out,depth+1);}}catch(e){}}"
                    + "function hasAny(o){for(var k in o){if(Object.prototype.hasOwnProperty.call(o,k))return true;}return false;}"
                    + "function addSignal(type,u,text){try{var params=parseParams(u);var fields={};if(text&&typeof text==='string'){var s=text.trim();if((s.charAt(0)==='{'||s.charAt(0)==='[')&&s.length<120000){try{collectIds(JSON.parse(s),fields,0);}catch(e){}}}if(!hasAny(params)&&!hasAny(fields))return;var store=w.__idlefishNetworkSignals||(w.__idlefishNetworkSignals=[]);store.push({type:type,url:baseUrl(u),url_params:params,fields:fields,ts:Date.now()});while(store.length>20)store.shift();}catch(e){}}"
                    + "function collectMeta(obj,out,depth){if(!obj||depth>4)return;try{if(typeof obj==='string'){var s=obj.trim();if((s.charAt(0)==='{'||s.charAt(0)==='[')&&s.length<120000){try{collectMeta(JSON.parse(s),out,depth+1);}catch(e){}}return;}if(Array.isArray(obj)){for(var i=0;i<obj.length&&i<50;i++)collectMeta(obj[i],out,depth+1);return;}if(typeof obj!=='object')return;var n=0;for(var k in obj){if(!Object.prototype.hasOwnProperty.call(obj,k)||sensitive.test(k))continue;var v=obj[k];var nk=String(k).replace(/[_-]/g,'').toLowerCase();if((nk==='api'||nk==='apiname'||nk==='v'||nk==='version'||nk==='method'||nk==='name')&&(typeof v==='string'||typeof v==='number'))out[k]=safeString(v);if(n++<50)collectMeta(v,out,depth+1);}}catch(e){}}"
                    + "function collectFromArg(arg,fields,params,meta){try{if(arg==null)return;collectMeta(arg,meta,0);if(typeof arg==='string'){var s=arg.trim();var ps=parseParams(s);for(var pk in ps){params[pk]=ps[pk];}if((s.charAt(0)==='{'||s.charAt(0)==='[')&&s.length<120000){try{collectIds(JSON.parse(s),fields,0);}catch(e){}}return;}if(typeof arg==='object'){collectIds(arg,fields,0);}}catch(e){}}"
                    + "function safeCallName(v){v=safeString(v);return sensitive.test(v)?'[redacted]':v;}"
                    + "function addBridgeSignal(source,args,result){try{var fields={};var params={};var meta={};for(var i=0;i<args.length&&i<8;i++)collectFromArg(args[i],fields,params,meta);collectFromArg(result,fields,params,meta);var call={source:source,ts:Date.now(),fields:fields,url_params:params,bridge_meta:meta};if(args.length>0&&(typeof args[0]==='string'||typeof args[0]==='number'))call.module=safeCallName(args[0]);if(args.length>1&&(typeof args[1]==='string'||typeof args[1]==='number'))call.method=safeCallName(args[1]);if(!hasAny(fields)&&!hasAny(params)&&!hasAny(meta)&&!call.module&&!call.method)return;var store=w.__idlefishBridgeSignals||(w.__idlefishBridgeSignals=[]);store.push(call);while(store.length>30)store.shift();}catch(e){}}"
                    + "function wrapBridgeFunction(owner,name){try{var fn=owner&&owner[name];if(typeof fn!=='function'||fn.__idlefishWrapped)return;var wrapped=function(){var args=Array.prototype.slice.call(arguments);addBridgeSignal(name,args,null);for(var i=0;i<args.length;i++){if(typeof args[i]==='function'){(function(index,orig){args[index]=function(){try{addBridgeSignal(name,args,arguments&&arguments.length?arguments[0]:null);}catch(e){}return orig.apply(this,arguments);};})(i,args[i]);}}return fn.apply(this,args);};wrapped.__idlefishWrapped=true;owner[name]=wrapped;}catch(e){}}"
                    + "if(!w.__idlefishNetworkHookInstalled){w.__idlefishNetworkHookInstalled=true;"
                    + "if(typeof w.fetch==='function'){var oldFetch=w.fetch;w.fetch=function(input,init){var u=(typeof input==='string')?input:(input&&input.url);return oldFetch.apply(this,arguments).then(function(resp){try{var c=resp.clone();c.text().then(function(t){addSignal('fetch',u,t);}).catch(function(){});}catch(e){}return resp;});};}"
                    + "if(w.XMLHttpRequest&&w.XMLHttpRequest.prototype){var oldOpen=w.XMLHttpRequest.prototype.open;w.XMLHttpRequest.prototype.open=function(m,u){this.__idlefishUrl=u;return oldOpen.apply(this,arguments);};var oldSend=w.XMLHttpRequest.prototype.send;w.XMLHttpRequest.prototype.send=function(){try{this.addEventListener('loadend',function(){try{addSignal('xhr',this.__idlefishUrl,this.responseText);}catch(e){}});}catch(e){}return oldSend.apply(this,arguments);};}"
                    + "}"
                    + "if(!w.__idlefishBridgeHookInstalled&&w.WindVane){w.__idlefishBridgeHookInstalled=true;wrapBridgeFunction(w.WindVane,'call');wrapBridgeFunction(w.WindVane,'call2');wrapBridgeFunction(w.WindVane,'postMessage');wrapBridgeFunction(w.WindVane,'fireEvent');}"
                    + "return JSON.stringify({"
                    + "href:String(location.href||''),"
                    + "title:String(document.title||''),"
                    + "text:String((document.body&&document.body.innerText)||'').slice(0,12000),"
                    + "textLength:String(((document.body&&document.body.innerText)||'').length),"
                    + "networkSignals:(w.__idlefishNetworkSignals||[]),"
                    + "bridgeSignals:(w.__idlefishBridgeSignals||[])"
                    + "});}catch(e){return JSON.stringify({error:String(e)});}})();";
            evaluateJavascript.invoke(webView, script, (ValueCallback<String>) value ->
                    handleJsResult(activity, probeKey, webView.getClass().getName(), value));
            Log.i(TAG, "evaluateJavascript invoked webView=" + webView.getClass().getName());
        } catch (Throwable throwable) {
            Log.e(TAG, "probe failed", throwable);
        }
    }

    private static View findWebView(View view) {
        if (view == null) {
            return null;
        }
        String className = view.getClass().getName();
        if (className.contains("WebView")) {
            return view;
        }
        if (!(view instanceof ViewGroup)) {
            return null;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            View found = findWebView(group.getChildAt(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void handleJsResult(Activity activity, String probeKey, String webViewClass, String value) {
        try {
            JSONObject data = parseJsValue(value);
            String text = data.optString("text", "");
            String href = data.optString("href", "");
            boolean isServiceScorePage = isServiceScorePage(href, text);
            boolean isWorkbenchPage = isWorkbenchPage(href, text);
            Log.i(TAG, "js result href=" + href
                    + ", version=" + PROBE_VERSION
                    + ", user=" + VUserHandle.myUserId()
                    + ", title=" + data.optString("title", "")
                    + ", textLength=" + text.length()
                    + ", networkSignals=" + networkSignalsCount(data)
                    + ", bridgeSignals=" + arrayCount(data, "bridgeSignals")
                    + ", serviceScorePage=" + isServiceScorePage
                    + ", workbenchPage=" + isWorkbenchPage
                    + ", snippet=" + snippet(text));

            if (isServiceScorePage) {
                String key = uploadKey("service", href);
                JSONObject event = buildServiceScoreEvent(activity, webViewClass, data);
                JSONObject metrics = event.optJSONObject("metrics");
                if (metrics == null || metrics.isNull("service_score")) {
                    Log.i(TAG, "service-score page not ready; skip upload key=" + key
                            + ", textLength=" + text.length()
                            + ", snippet=" + snippet(text));
                    return;
                }
                if (shouldUpload(key)) {
                    Log.i(TAG, "upload service-score event key=" + key
                            + ", score=" + metrics.opt("service_score"));
                    IdlefishEventSyncer.enqueueAndUploadIfEnabled(
                            VirtualCore.get().getContext(),
                            event.toString());
                } else {
                    Log.i(TAG, "skip duplicate service-score upload key=" + key
                            + ", ttlMs=" + UPLOAD_DEDUP_MS);
                }
            }
            if (isWorkbenchPage) {
                String key = uploadKey("identity", href);
                if (shouldUpload(key)) {
                    Log.i(TAG, "upload identity event key=" + key);
                    IdlefishEventSyncer.uploadDiscoveryOrEnqueueIfEnabled(
                            VirtualCore.get().getContext(),
                            buildIdentityEvent(activity, webViewClass, data).toString());
                } else {
                    Log.i(TAG, "skip duplicate identity upload key=" + key
                            + ", ttlMs=" + UPLOAD_DEDUP_MS);
                }
            }
        } catch (Throwable throwable) {
            Log.e(TAG, "handleJsResult failed value=" + value, throwable);
        }
    }

    private static String uploadKey(String type, String href) {
        String safeHref = href == null ? "" : href;
        int hashIndex = safeHref.indexOf('#');
        if (hashIndex >= 0) {
            safeHref = safeHref.substring(0, hashIndex);
        }
        return type + ":u" + VUserHandle.myUserId() + ":" + safeHref;
    }

    private static boolean shouldUpload(String key) {
        long now = System.currentTimeMillis();
        synchronized (UPLOADED_AT) {
            Long lastUploadAt = UPLOADED_AT.get(key);
            if (lastUploadAt != null && now - lastUploadAt < UPLOAD_DEDUP_MS) {
                return false;
            }
            UPLOADED_AT.put(key, now);
            if (UPLOADED_AT.size() > 200) {
                UPLOADED_AT.clear();
                UPLOADED_AT.put(key, now);
            }
            return true;
        }
    }

    private static JSONObject parseJsValue(String value) throws Exception {
        Object parsed = new JSONTokener(value == null ? "null" : value).nextValue();
        if (parsed instanceof JSONObject) {
            return (JSONObject) parsed;
        }
        if (parsed instanceof String) {
            return new JSONObject((String) parsed);
        }
        return new JSONObject();
    }

    private static JSONObject buildServiceScoreEvent(Activity activity, String webViewClass, JSONObject data) throws Exception {
        String capturedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
        String text = data.optString("text", "");
        String href = data.optString("href", "");
        String[] lines = normalizedLines(text);

        JSONObject webIdentity = extractWebIdentityFromUrl(href);
        JSONObject account = buildAccount(VirtualCore.get().getContext(), webIdentity);

        JSONObject page = new JSONObject();
        page.put("activity", activity.getClass().getName());
        page.put("url", sanitizeUrlForUpload(href));
        page.put("url_params", webIdentity.optJSONObject("url_params"));

        JSONObject metrics = new JSONObject();
        put(metrics, "category", findCategory(lines));
        put(metrics, "updated_date", findUpdatedDate(lines));
        put(metrics, "service_score", findServiceScore(lines));
        put(metrics, "service_score_peer_status", findFirstPeerStatus(lines));
        put(metrics, "item_quality_score", numberAfterLabel(lines, labelItemQuality()));
        put(metrics, "response_speed_score", numberAfterLabel(lines, labelResponseSpeed()));
        put(metrics, "logistics_score", numberAfterLabel(lines, labelLogistics()));
        put(metrics, "after_sales_score", numberAfterLabel(lines, labelAfterSales()));
        put(metrics, "quality_refund_rate_percent", percentAfterLabel(lines, labelQualityRefund()));
        put(metrics, "punished_item_ratio_percent", percentAfterLabel(lines, labelPunishedItem()));
        put(metrics, "risk_item_ratio_percent", percentAfterLabel(lines, labelRiskItem()));
        put(metrics, "description_service_coverage_percent", percentAfterLabel(lines, labelDescriptionCoverage()));
        put(metrics, "thirty_minute_response_rate_percent", percentAfterLabel(lines, labelThirtyMinuteResponse()));
        put(metrics, "average_response_time", firstMatch(lines, "^\\d+" + Pattern.quote(labelMinute()) + "$"));
        put(metrics, "delivery_refund_rate_percent", percentAfterLabel(lines, labelDeliveryRefund()));
        put(metrics, "forty_eight_hour_delivery_rate_percent", percentAfterLabel(lines, labelFortyEightHourDelivery()));
        put(metrics, "fast_delivery_coverage_percent", percentAfterLabel(lines, labelFastDeliveryCoverage()));
        put(metrics, "complaint_confirmed_count", firstMatch(lines, "^\\d+" + Pattern.quote(labelCountUnit()) + "$"));
        put(metrics, "extra_score", numberAfterLabel(lines, labelExtraScore()));

        JSONObject raw = new JSONObject();
        raw.put("ui_texts", new JSONArray(lines));
        raw.put("webview_class", webViewClass);
        raw.put("title", data.optString("title", ""));
        raw.put("url_params", webIdentity.optJSONObject("url_params"));
        raw.put("web_identity", webIdentity);
        raw.put("web_network_signals", optArrayOrEmpty(data, "networkSignals"));
        raw.put("web_bridge_signals", optArrayOrEmpty(data, "bridgeSignals"));
        raw.put("text", text);
        raw.put("text_length", data.optInt("textLength", text.length()));
        raw.put("collector_version", PROBE_VERSION);
        raw.put("api", JSONObject.NULL);
        raw.put("payload_hash", JSONObject.NULL);

        JSONObject event = new JSONObject();
        event.put("event_type", "idlefish_shop_service_score");
        event.put("schema_version", 1);
        event.put("collector_version", PROBE_VERSION);
        event.put("event_id", UUID.randomUUID().toString());
        event.put("captured_at", capturedAt);
        event.put("source", "webview_js");
        putCommonIdentity(event, account);
        event.put("account", account);
        event.put("page", page);
        event.put("metrics", metrics);
        event.put("raw", raw);
        return event;
    }

    private static JSONObject buildIdentityEvent(Activity activity, String webViewClass, JSONObject data) throws Exception {
        String capturedAt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
        String text = data.optString("text", "");
        String href = data.optString("href", "");
        String[] lines = normalizedLines(text);
        JSONObject webIdentity = extractWebIdentityFromUrl(href);
        JSONObject account = buildAccount(VirtualCore.get().getContext(), webIdentity);

        JSONObject page = new JSONObject();
        page.put("activity", activity.getClass().getName());
        page.put("url", sanitizeUrlForUpload(href));
        page.put("url_params", webIdentity.optJSONObject("url_params"));

        JSONObject identity = new JSONObject();
        put(identity, "shop_name", findWorkbenchShopName(lines));
        put(identity, "category", findWorkbenchCategory(lines));
        put(identity, "service_score", findWorkbenchServiceScore(lines));
        copyIfPresent(identity, webIdentity, "user_id");
        copyIfPresent(identity, webIdentity, "seller_id");
        copyIfPresent(identity, webIdentity, "shop_id");
        copyIfPresent(identity, webIdentity, "shop_no");
        copyIfPresent(identity, webIdentity, "account_id");

        JSONObject metrics = new JSONObject();
        put(metrics, "category", identity.opt("category"));
        put(metrics, "service_score", identity.opt("service_score"));

        JSONObject raw = new JSONObject();
        raw.put("ui_texts", new JSONArray(lines));
        raw.put("webview_class", webViewClass);
        raw.put("title", data.optString("title", ""));
        raw.put("url_params", webIdentity.optJSONObject("url_params"));
        raw.put("web_identity", webIdentity);
        raw.put("web_network_signals", optArrayOrEmpty(data, "networkSignals"));
        raw.put("web_bridge_signals", optArrayOrEmpty(data, "bridgeSignals"));
        raw.put("text", text);
        raw.put("text_length", data.optInt("textLength", text.length()));
        raw.put("collector_version", PROBE_VERSION);
        raw.put("api", JSONObject.NULL);
        raw.put("payload_hash", JSONObject.NULL);

        JSONObject event = new JSONObject();
        event.put("event_type", "idlefish_shop_identity");
        event.put("schema_version", 1);
        event.put("collector_version", PROBE_VERSION);
        event.put("event_id", UUID.randomUUID().toString());
        event.put("captured_at", capturedAt);
        event.put("source", "webview_js");
        putCommonIdentity(event, account);
        event.put("account", account);
        event.put("identity", identity);
        event.put("metrics", metrics);
        event.put("page", page);
        event.put("raw", raw);
        return event;
    }

    private static JSONObject buildAccount(Context context, JSONObject webIdentity) throws Exception {
        int virtualUserId = VUserHandle.myUserId();
        String deviceId = android.os.Build.SERIAL;
        String deviceNo = IdlefishSyncConfig.getDeviceNo(context);
        Object shopId = webIdentity.opt("shop_id");
        JSONObject account = new JSONObject();
        account.put("device_no", deviceNo.length() == 0 ? JSONObject.NULL : deviceNo);
        account.put("device_id", deviceId);
        account.put("device_serial", deviceId);
        account.put("collector_type", "virtualapp_webview_js");
        account.put("collector_version", PROBE_VERSION);
        account.put("app_slot", virtualUserId + 1);
        account.put("package_name", TARGET_PACKAGE);
        account.put("virtual_user_id", virtualUserId);
        account.put("shop_id", shopId == null ? JSONObject.NULL : shopId);
        copyIfPresent(account, webIdentity, "user_id");
        copyIfPresent(account, webIdentity, "seller_id");
        copyIfPresent(account, webIdentity, "shop_no");
        copyIfPresent(account, webIdentity, "account_id");
        account.put("account_alias", JSONObject.NULL);
        return account;
    }

    private static void putCommonIdentity(JSONObject event, JSONObject account) throws Exception {
        event.put("device_no", account.opt("device_no"));
        event.put("device_id", account.optString("device_id", ""));
        event.put("collector_type", account.optString("collector_type", ""));
        event.put("app_slot", account.optInt("app_slot"));
        event.put("virtual_user_id", account.optInt("virtual_user_id"));
    }

    private static String[] normalizedLines(String text) {
        if (text == null || text.length() == 0) {
            return new String[0];
        }
        String[] rawLines = text.split("\\r?\\n");
        JSONArray clean = new JSONArray();
        for (String rawLine : rawLines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.length() > 0) {
                clean.put(line);
            }
        }
        String[] lines = new String[clean.length()];
        for (int i = 0; i < clean.length(); i++) {
            lines[i] = clean.optString(i);
        }
        return lines;
    }

    private static boolean containsServiceScoreText(String text) {
        return text != null && text.contains(labelServiceScore());
    }

    private static boolean isServiceScorePage(String href, String text) {
        boolean serviceRoute = href != null && href.contains("service-points");
        return serviceRoute || (containsServiceScoreText(text)
                && text != null
                && text.contains(labelCategory())
                && text.contains(labelAllMetrics()));
    }

    private static boolean isWorkbenchPage(String href, String text) {
        return text != null && text.contains(labelWorkbench()) && text.contains(labelSettings());
    }

    private static String findWorkbenchShopName(String[] lines) {
        int settingsIndex = indexOfEquals(lines, labelSettings());
        if (settingsIndex >= 0) {
            String candidate = firstLikelyShopName(lines, settingsIndex + 1, Math.min(lines.length, settingsIndex + 5));
            if (candidate != null) {
                return candidate;
            }
        }
        int workbenchIndex = indexOfContains(lines, labelWorkbench());
        if (workbenchIndex >= 0) {
            return firstLikelyShopName(lines, workbenchIndex + 1, Math.min(lines.length, workbenchIndex + 8));
        }
        return null;
    }

    private static String firstLikelyShopName(String[] lines, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            String line = lines[i];
            if (line.length() == 0 || line.equals(labelSettings()) || line.equals(labelSubscribeService())
                    || line.equals(labelWorkbench()) || line.contains(labelServiceScore())
                    || line.contains(labelJoinClub()) || line.contains(labelYesterdayData())) {
                continue;
            }
            if (line.contains(labelMiddleDot())) {
                continue;
            }
            if (NUMBER_PATTERN.matcher(line).matches()) {
                continue;
            }
            return line;
        }
        return null;
    }

    private static String findWorkbenchCategory(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int index = line.indexOf(labelMiddleDot());
            if (index > 0) {
                return line.substring(0, index).trim();
            }
            if (index == 0 && i > 0) {
                return lines[i - 1];
            }
        }
        return null;
    }

    private static Double findWorkbenchServiceScore(String[] lines) {
        int index = indexOfContains(lines, labelServiceScore());
        return index >= 0 ? firstNumberAfter(lines, index + 1, Math.min(lines.length, index + 5)) : null;
    }

    private static String findCategory(String[] lines) {
        String prefix = labelCategory() + ":";
        String fullWidthPrefix = labelCategory() + text(0xFF1A);
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
            if (line.startsWith(fullWidthPrefix)) {
                return line.substring(fullWidthPrefix.length()).trim();
            }
        }
        return null;
    }

    private static String findUpdatedDate(String[] lines) {
        Pattern pattern = Pattern.compile("(\\d{4})\\.(\\d{2})\\.(\\d{1,2})");
        for (String line : lines) {
            if (!line.contains(labelUpdated())) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return String.format(Locale.US, "%s-%s-%02d",
                        matcher.group(1), matcher.group(2), Integer.parseInt(matcher.group(3)));
            }
        }
        return null;
    }

    private static Double findServiceScore(String[] lines) {
        int updatedIndex = indexOfContains(lines, labelUpdated());
        if (updatedIndex >= 0) {
            Double score = firstNumberAfter(lines, updatedIndex + 1, Math.min(lines.length, updatedIndex + 5));
            if (score != null) {
                return score;
            }
        }
        int scoreLabel = indexOfEquals(lines, labelServiceScore());
        return scoreLabel >= 0 ? firstNumberAfter(lines, scoreLabel + 1, lines.length) : null;
    }

    private static String findFirstPeerStatus(String[] lines) {
        Pattern combined = Pattern.compile("^(" + Pattern.quote(labelHigherThan()) + "|"
                + Pattern.quote(labelExceeding()) + "|"
                + Pattern.quote(labelLagging()) + ")\\d+%" + Pattern.quote(labelPeer()) + "$");
        for (String line : lines) {
            if (combined.matcher(line).matches()) {
                return line;
            }
        }
        for (int i = 0; i + 2 < lines.length; i++) {
            if ((labelHigherThan().equals(lines[i]) || labelExceeding().equals(lines[i]) || labelLagging().equals(lines[i]))
                    && PERCENT_PATTERN.matcher(lines[i + 1]).matches()
                    && labelPeer().equals(lines[i + 2])) {
                return lines[i] + lines[i + 1] + lines[i + 2];
            }
        }
        return null;
    }

    private static Double numberAfterLabel(String[] lines, String label) {
        int index = indexOfEquals(lines, label);
        return index >= 0 ? firstNumberAfter(lines, index + 1, lines.length) : null;
    }

    private static Integer percentAfterLabel(String[] lines, String label) {
        int index = indexOfEquals(lines, label);
        if (index < 0) {
            return null;
        }
        for (int i = index + 1; i < lines.length; i++) {
            Matcher matcher = PERCENT_PATTERN.matcher(lines[i]);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        return null;
    }

    private static Double firstNumberAfter(String[] lines, int startInclusive, int endExclusive) {
        for (int i = startInclusive; i < endExclusive; i++) {
            if (NUMBER_PATTERN.matcher(lines[i]).matches()) {
                return Double.parseDouble(lines[i]);
            }
        }
        return null;
    }

    private static String firstMatch(String[] lines, String pattern) {
        Pattern compiled = Pattern.compile(pattern);
        for (String line : lines) {
            if (compiled.matcher(line).matches()) {
                return line;
            }
        }
        return null;
    }

    private static int indexOfEquals(String[] lines, String expected) {
        for (int i = 0; i < lines.length; i++) {
            if (expected.equals(lines[i])) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfContains(String[] lines, String expected) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(expected)) {
                return i;
            }
        }
        return -1;
    }

    private static void put(JSONObject object, String key, Object value) throws Exception {
        object.put(key, value == null ? JSONObject.NULL : value);
    }

    private static void copyIfPresent(JSONObject target, JSONObject source, String key) throws Exception {
        if (source != null && source.has(key) && !source.isNull(key)) {
            target.put(key, source.opt(key));
        }
    }

    private static int networkSignalsCount(JSONObject data) {
        return arrayCount(data, "networkSignals");
    }

    private static int arrayCount(JSONObject data, String key) {
        JSONArray signals = data.optJSONArray(key);
        return signals == null ? 0 : signals.length();
    }

    private static JSONArray optArrayOrEmpty(JSONObject data, String key) {
        JSONArray array = data.optJSONArray(key);
        return array == null ? new JSONArray() : array;
    }

    private static JSONObject extractWebIdentityFromUrl(String href) throws Exception {
        JSONObject params = extractWhitelistedUrlParams(href);
        JSONObject identity = new JSONObject();
        identity.put("url_params", params);
        putFirstParam(identity, params, "user_id", "userId", "userid", "user_id",
                "targetUserId", "target_user_id", "fishUserId", "fish_user_id",
                "xyUserId", "xy_user_id", "xianyuUserId", "xianyu_user_id");
        putFirstParam(identity, params, "seller_id", "sellerId", "sellerid", "seller_id",
                "sellerUserId", "seller_user_id");
        putFirstParam(identity, params, "shop_id", "shopId", "shopid", "shop_id",
                "shopUserId", "shop_user_id");
        putFirstParam(identity, params, "shop_no", "shopNo", "shop_no");
        putFirstParam(identity, params, "account_id", "accountId", "account_id");
        return identity;
    }

    private static JSONObject extractWhitelistedUrlParams(String href) throws Exception {
        JSONObject params = new JSONObject();
        if (href == null || href.length() == 0) {
            return params;
        }

        collectParamsFromSegment(params, substringBetween(href, "?", "#"));
        int hashIndex = href.indexOf('#');
        if (hashIndex >= 0 && hashIndex + 1 < href.length()) {
            String hash = href.substring(hashIndex + 1);
            collectParamsFromSegment(params, substringAfter(hash, "?"));
            collectParamsFromSegment(params, hash);
        }
        return params;
    }

    private static void collectParamsFromSegment(JSONObject params, String segment) throws Exception {
        if (segment == null || segment.length() == 0) {
            return;
        }
        int questionIndex = segment.indexOf('?');
        if (questionIndex >= 0) {
            segment = segment.substring(questionIndex + 1);
        }
        int hashIndex = segment.indexOf('#');
        if (hashIndex >= 0) {
            segment = segment.substring(0, hashIndex);
        }
        for (String pair : segment.split("&")) {
            if (pair.length() == 0 || pair.indexOf('=') <= 0) {
                continue;
            }
            int equalIndex = pair.indexOf('=');
            String key = safeDecode(pair.substring(0, equalIndex)).trim();
            String value = safeDecode(pair.substring(equalIndex + 1)).trim();
            if (key.length() == 0 || value.length() == 0) {
                continue;
            }
            if (SENSITIVE_URL_KEY_PATTERN.matcher(key).find()) {
                continue;
            }
            if (!URL_ID_PARAM_KEYS.contains(key.toLowerCase(Locale.US))) {
                continue;
            }
            params.put(key, value.length() > 256 ? value.substring(0, 256) : value);
        }
    }

    private static void putFirstParam(JSONObject target, JSONObject params, String normalizedKey, String... candidates) throws Exception {
        for (String candidate : candidates) {
            Object value = findParamValueIgnoreCase(params, candidate);
            if (value != null) {
                target.put(normalizedKey, value);
                return;
            }
        }
    }

    private static Object findParamValueIgnoreCase(JSONObject params, String key) {
        JSONArray names = params.names();
        if (names == null) {
            return null;
        }
        for (int i = 0; i < names.length(); i++) {
            String name = names.optString(i);
            if (name.equalsIgnoreCase(key) && !params.isNull(name)) {
                return params.opt(name);
            }
        }
        return null;
    }

    private static String sanitizeUrlForUpload(String href) {
        if (href == null || href.length() == 0) {
            return "";
        }
        String[] hashSplit = href.split("#", 2);
        String beforeHash = redactSensitiveParamsInUrlPart(hashSplit[0]);
        if (hashSplit.length == 1) {
            return beforeHash;
        }
        return beforeHash + "#" + redactSensitiveParamsInUrlPart(hashSplit[1]);
    }

    private static String redactSensitiveParamsInUrlPart(String urlPart) {
        int questionIndex = urlPart.indexOf('?');
        if (questionIndex < 0) {
            return urlPart;
        }
        String prefix = urlPart.substring(0, questionIndex + 1);
        String query = urlPart.substring(questionIndex + 1);
        String[] pairs = query.split("&", -1);
        StringBuilder builder = new StringBuilder(prefix);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                builder.append('&');
            }
            String pair = pairs[i];
            int equalIndex = pair.indexOf('=');
            if (equalIndex <= 0) {
                builder.append(pair);
                continue;
            }
            String key = safeDecode(pair.substring(0, equalIndex));
            if (SENSITIVE_URL_KEY_PATTERN.matcher(key).find()) {
                builder.append(pair, 0, equalIndex + 1).append("[redacted]");
            } else {
                builder.append(pair);
            }
        }
        return builder.toString();
    }

    private static String substringBetween(String value, String startToken, String endToken) {
        int start = value.indexOf(startToken);
        if (start < 0) {
            return "";
        }
        start += startToken.length();
        int end = value.indexOf(endToken, start);
        return end < 0 ? value.substring(start) : value.substring(start, end);
    }

    private static String substringAfter(String value, String token) {
        int index = value.indexOf(token);
        return index < 0 ? "" : value.substring(index + token.length());
    }

    private static String safeDecode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Throwable ignored) {
            return value;
        }
    }

    private static String text(int... codePoints) {
        StringBuilder builder = new StringBuilder();
        for (int codePoint : codePoints) {
            builder.appendCodePoint(codePoint);
        }
        return builder.toString();
    }

    private static String labelServiceScore() {
        return text(0x5C0F, 0x94FA, 0x670D, 0x52A1, 0x5206);
    }

    private static String labelWorkbench() {
        return text(0x9C7C, 0x5C0F, 0x94FA, 0x5DE5, 0x4F5C, 0x53F0);
    }

    private static String labelSettings() {
        return text(0x8BBE, 0x7F6E);
    }

    private static String labelSubscribeService() {
        return text(0x8BA2, 0x9605, 0x670D, 0x52A1, 0x53F7);
    }

    private static String labelJoinClub() {
        return text(0x52A0, 0x5165, 0x8D85, 0x8D5E, 0x4FF1, 0x4E50, 0x90E8);
    }

    private static String labelYesterdayData() {
        return text(0x5C0F, 0x94FA, 0x6628, 0x65E5, 0x6570, 0x636E);
    }

    private static String labelAllMetrics() {
        return text(0x5168, 0x90E8, 0x6307, 0x6807, 0x8868, 0x73B0);
    }

    private static String labelMiddleDot() {
        return text(0x00B7);
    }

    private static String labelCategory() {
        return text(0x8003, 0x6838, 0x7C7B, 0x76EE);
    }

    private static String labelUpdated() {
        return text(0x66F4, 0x65B0);
    }

    private static String labelHigherThan() {
        return text(0x9AD8, 0x4E8E);
    }

    private static String labelExceeding() {
        return text(0x8D85, 0x8FC7);
    }

    private static String labelLagging() {
        return text(0x843D, 0x540E);
    }

    private static String labelPeer() {
        return text(0x540C, 0x884C);
    }

    private static String labelItemQuality() {
        return text(0x5B9D, 0x8D1D, 0x8D28, 0x91CF);
    }

    private static String labelResponseSpeed() {
        return text(0x54CD, 0x5E94, 0x901F, 0x5EA6);
    }

    private static String labelLogistics() {
        return text(0x7269, 0x6D41, 0x4F53, 0x9A8C);
    }

    private static String labelAfterSales() {
        return text(0x552E, 0x540E, 0x4F53, 0x9A8C);
    }

    private static String labelQualityRefund() {
        return text(0x54C1, 0x8D28, 0x539F, 0x56E0, 0x9000, 0x6B3E, 0x7387);
    }

    private static String labelPunishedItem() {
        return text(0x5904, 0x7F5A, 0x5B9D, 0x8D1D, 0x5360, 0x6BD4);
    }

    private static String labelRiskItem() {
        return text(0x98CE, 0x9669, 0x5B9D, 0x8D1D, 0x5360, 0x6BD4);
    }

    private static String labelDescriptionCoverage() {
        return text(0x300C, 0x771F, 0x5B9E, 0x63CF, 0x8FF0, 0x300D,
                0x76F8, 0x5173, 0x670D, 0x52A1, 0x8986, 0x76D6, 0x7387);
    }

    private static String labelThirtyMinuteResponse() {
        return text(0x0033, 0x0030, 0x5206, 0x949F, 0x56DE, 0x590D, 0x7387);
    }

    private static String labelMinute() {
        return text(0x5206, 0x949F);
    }

    private static String labelDeliveryRefund() {
        return text(0x53D1, 0x8D27, 0x539F, 0x56E0, 0x9000, 0x6B3E, 0x7387);
    }

    private static String labelFortyEightHourDelivery() {
        return text(0x0034, 0x0038, 0x5C0F, 0x65F6, 0x53D1, 0x8D27, 0x7387);
    }

    private static String labelFastDeliveryCoverage() {
        return text(0x300C, 0x6781, 0x901F, 0x53D1, 0x8D27, 0x300D,
                0x670D, 0x52A1, 0x8986, 0x76D6, 0x7387);
    }

    private static String labelCountUnit() {
        return text(0x4E2A);
    }

    private static String labelExtraScore() {
        return text(0x9644, 0x52A0, 0x5206);
    }

    private static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > 160 ? oneLine.substring(0, 160) : oneLine;
    }
}
