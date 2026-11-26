package com.doubao.fedterminal;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 美联储周期 · 手机端 v1.5
 * 页面与曲线全离线；方向一格改为 2026-11-26 的芝商所快照，并显示“距会议 N 天 / 已过期”。
 * 芝商所旧接口已下线，这里不再自动抓取，只留「打开芝商所」入口供核对。
 */
public class MainActivity extends Activity {

    private static final String TOOL_PAGE =
            "https://www.cmegroup.com/markets/interest-rates/cme-fedwatch-tool.html";

    private static final String PM_SEARCH =
            "https://gamma-api.polymarket.com/public-search?q=Fed%20decision&limit_per_type=10&events_status=active";
    private static final String PM_EVENTS =
            "https://gamma-api.polymarket.com/events?closed=false&limit=60&order=endDate&ascending=true";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private WebView web;
    private volatile boolean running = false;
    private volatile String lastLog = "";
    private volatile String lastOk = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        web = findViewById(R.id.web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setBackgroundColor(0xFFF7F3EA);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.addJavascriptInterface(new Bridge(), "FedBridge");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return true;   // 单页应用，不跳外部链接
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    class Bridge {
        @JavascriptInterface
        public void detail() { showDetail(); }

        @JavascriptInterface
        public void refresh() { doRefresh(); }

        @JavascriptInterface
        public void openCme() {
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TOOL_PAGE)));
                    } catch (Exception ignored) { }
                }
            });
        }
    }

    // ------------------------------------------------------------ 刷新（Polymarket）
    private void doRefresh() {
        if (running) return;
        running = true;
        new Thread(new Runnable() {
            @Override public void run() {
                String out;
                try {
                    JSONObject res = fetchPolymarket();
                    lastOk = res.toString();
                    lastLog = "时间：" + now() + "\n" + res.optString("log") + "\n结果：已更新";
                    out = res.toString();
                } catch (Exception e) {
                    lastLog = "时间：" + now() + "\n失败：" + describe(e);
                    out = fallback(reasonOf(e));
                }
                push(out);
                running = false;
            }
        }, "pm").start();
    }

    private JSONObject fetchPolymarket() throws Exception {
        String text = httpGet(PM_SEARCH);
        JSONArray evs = null;
        try { evs = new JSONObject(text).optJSONArray("events"); } catch (Exception ignored) { }
        JSONObject ev = pickEvent(evs);
        String used = "public-search";
        if (ev == null) {
            text = httpGet(PM_EVENTS);
            try { evs = new JSONArray(text); } catch (Exception e) { evs = null; }
            ev = pickEvent(evs);
            used = "events";
        }
        if (ev == null) throw new Exception("没找到 Fed decision 事件｜" + head(text));

        JSONArray ms = ev.optJSONArray("markets");
        if (ms == null) throw new Exception("事件里没有 markets");

        double hike = 0, hold = 0, cut = 0;
        int used2 = 0;
        StringBuilder detail = new StringBuilder();
        for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.optJSONObject(i);
            if (m == null) continue;
            if (m.optBoolean("closed", false)) continue;
            String title = firstNonEmpty(m.optString("groupItemTitle"), m.optString("question"));
            double p = yesPrice(m);
            if (p <= 0) continue;
            String low = title.toLowerCase(Locale.ROOT);
            if (low.contains("increase") || low.contains("hike") || low.contains("raise")) hike += p;
            else if (low.contains("decrease") || low.contains("cut") || low.contains("lower")) cut += p;
            else if (low.contains("no change") || low.contains("unchanged") || low.contains("hold")) hold += p;
            else continue;
            used2++;
            if (detail.length() < 90) detail.append(title).append("=").append(Math.round(p * 100)).append("% ");
        }
        double total = hike + hold + cut;
        if (used2 == 0 || total < 0.3) throw new Exception("事件概率解析为空｜" + head(new JSONObject().put("title", ev.optString("title")).toString()));

        JSONObject meeting = new JSONObject();
        meeting.put("date", meetingDate(ev));
        meeting.put("hike", hike / total);
        meeting.put("hold", hold / total);
        meeting.put("cut", cut / total);

        JSONObject res = new JSONObject();
        res.put("ok", true);
        res.put("meeting", meeting);
        res.put("asOf", isoNow());
        res.put("source", "Polymarket");
        res.put("log", "来源：" + used + "｜事件 " + ev.optString("title") + "｜" + detail);
        return res;
    }

    private static JSONObject pickEvent(JSONArray evs) {
        if (evs == null) return null;
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        JSONObject best = null;
        for (int i = 0; i < evs.length(); i++) {
            JSONObject ev = evs.optJSONObject(i);
            if (ev == null) continue;
            String title = ev.optString("title", "").toLowerCase(Locale.ROOT);
            if (!(title.contains("fed decision") || title.contains("fomc"))) continue;
            if (ev.optBoolean("closed", false)) continue;
            String end = ev.optString("endDate", "");
            if (end.length() < 10) continue;
            String day = end.substring(0, 10);
            if (day.compareTo(today) < 0) continue;
            if (best == null || day.compareTo(best.optString("endDate", "").substring(0, 10)) < 0) best = ev;
        }
        return best;
    }

    private static String meetingDate(JSONObject ev) {
        String end = ev.optString("endDate", "");
        if (end.length() >= 10) return end.substring(0, 10);
        return "";
    }

    private static double yesPrice(JSONObject m) {
        String s = m.optString("outcomePrices", "");
        if (s.isEmpty()) return 0;
        try {
            JSONArray prices = new JSONArray(s);
            JSONArray outs = new JSONArray(m.optString("outcomes", "[]"));
            int idx = 0;
            for (int i = 0; i < outs.length(); i++) {
                if ("yes".equalsIgnoreCase(outs.optString(i))) { idx = i; break; }
            }
            double v = prices.optDouble(idx, 0);
            if (v > 1.5) v /= 100;
            return v;
        } catch (Exception e) {
            return 0;
        }
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(12000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        int code = c.getResponseCode();
        if (code != 200) {
            String body = snippet(code >= 400 ? c.getErrorStream() : c.getInputStream());
            c.disconnect();
            throw new Exception("HTTP " + code + (body.isEmpty() ? "" : "｜" + body));
        }
        String t = readAll(c.getInputStream());
        c.disconnect();
        return t;
    }

    private void push(final String json) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (web != null) web.evaluateJavascript("window.onFedWatchData(" + JSONObject.quote(json) + ")", null);
            }
        });
    }

    private String fallback(String reason) {
        try {
            if (!lastOk.isEmpty()) {
                JSONObject res = new JSONObject(lastOk);
                res.put("ok", false);
                res.put("cache", true);
                res.put("reason", reason);
                return res.toString();
            }
        } catch (Exception ignored) { }
        try {
            JSONObject e = new JSONObject();
            e.put("ok", false);
            e.put("cache", false);
            e.put("reason", reason);
            return e.toString();
        } catch (Exception x) { return "{\"ok\":false,\"reason\":\"网络请求失败\"}"; }
    }

    private static String now() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    private static String isoNow() {
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        return iso.format(new Date());
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        return sb.toString();
    }

    private static String snippet(InputStream in) {
        try {
            String t = readAll(in).replaceAll("\\s+", " ").trim();
            return t.substring(0, Math.min(140, t.length()));
        } catch (Exception e) { return ""; }
    }

    private static String head(String t) {
        if (t == null) return "";
        String s = t.replaceAll("\\s+", " ").trim();
        return s.substring(0, Math.min(120, s.length()));
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String describe(Exception e) {
        if (e == null) return "未知错误";
        String m = e.getMessage();
        return (m == null || m.isEmpty() ? e.getClass().getSimpleName() : m);
    }

    private static String reasonOf(Exception e) {
        String m = describe(e);
        if (m.contains("UnknownHost") || m.contains("Unable to resolve")) return "域名解析失败（检查 VPN）";
        if (m.contains("超时") || m.contains("timed out")) return "连接超时";
        if (m.contains("HTTP 5")) return "预测市场服务端异常";
        if (m.contains("没找到")) return "没找到 Fed 会议事件";
        return m.length() > 70 ? m.substring(0, 70) + "…" : m;
    }

    private void showDetail() {
        final String msg = lastLog.isEmpty() ? "还没有刷新记录，先点一次「刷新预测市场」。" : lastLog;
        runOnUiThread(new Runnable() {
            @Override public void run() {
                ScrollView sv = new ScrollView(MainActivity.this);
                TextView tv = new TextView(MainActivity.this);
                tv.setText(msg);
                tv.setTextSize(12);
                tv.setTextIsSelectable(true);
                tv.setPadding(48, 40, 48, 24);
                sv.addView(tv);
                new android.app.AlertDialog.Builder(MainActivity.this)
                        .setTitle("刷新诊断").setView(sv).setPositiveButton("知道了", null).show();
            }
        });
    }
}
