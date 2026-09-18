package com.joy.wskeycopy;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 京东 wskey 本地提取模块。
 *
 * 设计原则：
 *  - 仅作用于 com.jingdong.app.mall
 *  - 不包含任何网络代码，不存在上传路径
 *  - 双路抓取：SharedPreferences 存储 + okhttp/HttpURLConnection 网络 Cookie
 *  - 命中后自动写入剪贴板并弹出「ck已复制」提示
 */
public class HookMain implements IXposedHookLoadPackage {

    private static final String TAG = "WskeyCopy";
    private static final String TARGET_PACKAGE = "com.jingdong.app.mall";

    /** 已复制的值，避免同一串被重复刷屏 */
    private static volatile String sLastCopied = null;

    private static Context sAppContext = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        log("进入京东进程: " + lpparam.processName);

        // 1) 拿到 Application，用于后续弹 Toast / 写剪贴板
        hookApplication(lpparam);

        // 2) 第一路：本地存储
        hookSharedPreferences(lpparam);

        // 3) 第二路：网络 Cookie
        hookOkHttp(lpparam);
        hookHttpURLConnection(lpparam);
    }

    // ---------------------------------------------------------------- 上下文

    private void hookApplication(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    Application.class, "onCreate", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sAppContext = (Context) param.thisObject;
                        }
                    });
        } catch (Throwable t) {
            log("hook Application 失败: " + t);
        }
    }

    // ------------------------------------------------------------ 第一路：存储

    /**
     * hook SharedPreferences 的读取。京东的登录态最终会落到 sp 里，
     * 无论它用 getString 还是 getAll，我们都能看到键值。
     */
    private void hookSharedPreferences(XC_LoadPackage.LoadPackageParam lpparam) {
        Class<?> spImpl;
        try {
            spImpl = XposedHelpers.findClass(
                    "android.app.SharedPreferencesImpl", lpparam.classLoader);
            log("找到 SharedPreferencesImpl，开始安装 hook");
        } catch (Throwable t) {
            log("找不到 SharedPreferencesImpl: " + t);
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(spImpl, "getString",
                    String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String key = (String) param.args[0];
                            Object val = param.getResult();
                            if (val instanceof String) {
                                checkAndCopy(key, (String) val);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("hook getString 失败: " + t);
        }

        try {
            XposedHelpers.findAndHookMethod(spImpl, "getAll", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object res = param.getResult();
                    if (res instanceof Map) {
                        for (Object o : ((Map<?, ?>) res).entrySet()) {
                            Map.Entry<?, ?> e = (Map.Entry<?, ?>) o;
                            if (e.getValue() instanceof String) {
                                checkAndCopy(String.valueOf(e.getKey()), (String) e.getValue());
                            }
                        }
                    }
                }
            });
        } catch (Throwable t) {
            log("hook getAll 失败: " + t);
        }
    }

    // ------------------------------------------------------------ 第二路：网络

    /**
     * hook okhttp3 的 Request 构造，从 Header 的 Cookie 里取 wskey。
     * 京东大量请求走 okhttp，这里命中率最高。
     */
    private void hookOkHttp(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> builder = XposedHelpers.findClass(
                    "okhttp3.Request$Builder", lpparam.classLoader);
            log("找到 okhttp3.Request$Builder，开始安装 hook");
            XposedHelpers.findAndHookMethod(builder, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object req = param.getResult();
                    if (req == null) return;
                    try {
                        // 1) 全量扫描所有 header（不只 Cookie）
                        Object headers = XposedHelpers.callMethod(req, "headers");
                        int size = (Integer) XposedHelpers.callMethod(headers, "size");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < size; i++) {
                            String hn = (String) XposedHelpers.callMethod(headers, "name", i);
                            String hv = (String) XposedHelpers.callMethod(headers, "value", i);
                            sb.append(hn).append("=[").append(hv).append("] ");
                            // header 名或值里出现 wskey/pin 都要检
                            checkAndCopy(hn, hv);
                        }
                        // 只在疑似含凭证时打印，避免刷屏
                        String all = sb.toString().toLowerCase();
                        if (all.contains("wskey") || all.contains("pin=")) {
                            log("okhttp headers: " + sb);
                        }

                        // 2) URL 与请求体也扫一遍
                        Object url = XposedHelpers.callMethod(req, "url");
                        if (url != null) {
                            checkAndCopy("url", url.toString());
                        }
                        Object body = XposedHelpers.callMethod(req, "body");
                        if (body != null) {
                            Object content = XposedHelpers.callMethod(body, "toString");
                            if (content instanceof String) {
                                checkAndCopy("body", (String) content);
                            }
                        }
                    } catch (Throwable t) {
                        log("okhttp build 处理异常: " + t);
                    }
                }
            });
            log("okhttp hook 安装成功");
        } catch (Throwable t) {
            log("未找到 okhttp3.Request$Builder —— " + t);
        }
    }

    /**
     * hook HttpURLConnection 设置请求头，兜底那些不走 okhttp 的请求。
     * 注意：这里记录所有 header，不只 Cookie。
     */
    private void hookHttpURLConnection(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    "java.net.URLConnection", lpparam.classLoader,
                    "setRequestProperty", String.class, String.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            String name = (String) param.args[0];
                            String value = (String) param.args[1];
                            if (name == null || value == null) return;
                            // 任何 header 都交给统一判定
                            checkAndCopy(name, value);
                        }
                    });
            log("URLConnection hook 安装成功");
        } catch (Throwable t) {
            log("hook setRequestProperty 失败: " + t);
        }
    }

    // ---------------------------------------------------------------- 判定

    /**
     * 判断某个键值里是否含有 wskey，命中就复制。
     */
    private static void checkAndCopy(String key, String value) {
        if (value == null || value.isEmpty()) return;

        String lv = value.toLowerCase();
        if (lv.contains("wskey") || lv.contains("pin=")) {
            log("【原始值】source=" + key
                    + " len=" + value.length()
                    + " | " + value);
        }

        String found = extractWskey(key, value);
        if (found == null) return;

        if (found.equals(sLastCopied)) return;
        sLastCopied = found;
        log("【命中】验证串长度=" + found.length() + " | " + found);
        copyToClipboard(found);
    }

    /**
     * 从键值中提取验证串，输出格式固定为：pin=xxx;wskey=yyy;
     *
     * 京东的登录态形态为 "pin=jd_xxx;wskey=AAJqm...;"，
     * 因此这里分别取 pin 与 wskey 两个字段再拼装，
     * 避免"整串复制"时把其它无关 Cookie 一起带出。
     */
    private static String extractWskey(String key, String value) {
        String pin = extractField(value, "pin");
        String wskey = extractField(value, "wskey");

        if (wskey == null) {
            // 兼容"value 本身就是纯 wskey"的情况
            if (key != null && key.toLowerCase().contains("wskey") && isValidWskey(value)) {
                wskey = value.trim();
            }
        }
        if (wskey == null) return null;

        // 组装成完整验证串
        if (pin != null) {
            return "pin=" + pin + ";wskey=" + wskey + ";";
        }
        return "wskey=" + wskey + ";";
    }

    /**
     * 从形如 "pin=xxx;wskey=yyy;" 的串中，取指定字段的值。
     * 找不到返回 null。
     */
    private static String extractField(String src, String field) {
        if (src == null) return null;
        String lower = src.toLowerCase();
        String needle = field.toLowerCase() + "=";
        int idx = lower.indexOf(needle);
        if (idx < 0) return null;

        String after = src.substring(idx + needle.length());
        int end = after.length();
        for (int i = 0; i < after.length(); i++) {
            if (isSeparator(after.charAt(i))) {
                end = i;
                break;
            }
        }
        String v = after.substring(0, end).trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * 是否为分隔符。同时兼容半角与全角标点。
     */
    private static boolean isSeparator(char c) {
        switch (c) {
            // 半角
            case ';': case ',': case '&': case '"': case '\'':
            case ' ': case '\t': case '\n': case '\r':
            // 全角
            case '\uFF1B': // ；
            case '\uFF0C': // ，
            case '\uFF1A': // ：
            case '\uFF06': // ＆
            case '\u3000': // 全角空格
                return true;
            default:
                return false;
        }
    }

    /**
     * wskey 的形态校验。
     * 仅长度下限 + 排除分隔符/空白/控制字符，不因非 ASCII 一概否决。
     */
    private static boolean isValidWskey(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.length() < 16) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c <= 0x20 || isSeparator(c)) return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- 输出

    private static void copyToClipboard(final String text) {
        final Context ctx = sAppContext;
        if (ctx == null) {
            log("没有拿到 Context，无法写剪贴板");
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    ClipboardManager cm = (ClipboardManager)
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("wskey", text));
                    }
                    Toast.makeText(ctx, "ck已复制", Toast.LENGTH_LONG).show();
                } catch (Throwable t) {
                    log("写剪贴板失败: " + t);
                }
            }
        });
    }

    private static void log(String msg) {
        XposedBridge.log(TAG + ": " + msg);
    }
}
