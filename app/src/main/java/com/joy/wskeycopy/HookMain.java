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
            XposedHelpers.findAndHookMethod(builder, "build", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object req = param.getResult();
                    if (req == null) return;
                    try {
                        Object headers = XposedHelpers.callMethod(req, "headers");
                        Object cookie = XposedHelpers.callMethod(headers, "get", "Cookie");
                        if (cookie == null) {
                            cookie = XposedHelpers.callMethod(headers, "get", "cookie");
                        }
                        if (cookie instanceof String) {
                            checkAndCopy("Cookie", (String) cookie);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("okhttp hook 已安装");
        } catch (Throwable t) {
            log("未找到 okhttp3，跳过（正常，可能被加固改名）");
        }
    }

    /**
     * hook HttpURLConnection 设置请求头，兜底那些不走 okhttp 的请求。
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
                            if (value != null && name != null
                                    && name.equalsIgnoreCase("Cookie")) {
                                checkAndCopy(name, value);
                            }
                        }
                    });
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

        // 调试：把原始值打出来，便于确认真实形态与分隔符
        if (value.toLowerCase().contains("wskey")) {
            log("原始值 key=" + key + " | value=" + value);
        }

        String found = extractWskey(key, value);
        if (found == null) return;

        if (found.equals(sLastCopied)) return;
        // 注意：这里不再"命中一次就永久停止"，改为允许更新为新值
        sLastCopied = found;
        log("命中 wskey, 长度=" + found.length() + " | 值=" + found);
        copyToClipboard(found);
    }

    /**
     * 从键值中提取 wskey 串。
     * 兼容两种形态：纯值（sp 里直接存的）与 Cookie 串（wskey=xxx; 形式）。
     */
    private static String extractWskey(String key, String value) {
        // 形态一：Cookie 串，找 wskey= 后面的部分
        String lower = value.toLowerCase();
        int idx = lower.indexOf("wskey=");
        if (idx >= 0) {
            String after = value.substring(idx + "wskey=".length());
            // 截到分隔符为止：兼容半角与全角（；，：）
            int end = after.length();
            for (int i = 0; i < after.length(); i++) {
                if (isSeparator(after.charAt(i))) {
                    end = i;
                    break;
                }
            }
            String token = after.substring(0, end).trim();
            if (isValidWskey(token)) return token;
        }

        // 形态二：整个 value 就是纯凭证（不含 "wskey=" 前缀，也不含分隔符）
        if (key != null && key.toLowerCase().contains("wskey") && isValidWskey(value)) {
            return value.trim();
        }

        return null;
    }

    /**
     * 是否为分隔符。同时兼容半角与全角标点，
     * 京东的 Cookie 串里常见全角分号（；U+FF1B）。
     */
    private static boolean isSeparator(char c) {
        switch (c) {
            // 半角
            case ';': case ',': case '&': case '"': case '\'':
            case ' ': case '\t': case '\n': case '\r': case ':':
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
