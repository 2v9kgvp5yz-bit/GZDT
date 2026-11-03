package com.gzmetro.sim;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;

/**
 * 广州地铁列车模拟：用 WebView 打开 assets/www/index.html。
 * - 只允许竖屏（见 AndroidManifest），运行时保持屏幕常亮
 * - 网页通过 window.AndroidTTS 调用系统文字转语音（WebView 自身不支持网页语音合成）
 * - 支持网页里的“导入音频文件”，可一次多选
 * - 页面以 https 虚拟地址加载，这样录音可以存进网页数据库（IndexedDB）
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final int PICK_AUDIO = 42;
    private static final int REQ_MIC = 43;
    private static final int REQ_ASR_UI = 44;

    private WebView web;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private ValueCallback<Uri[]> fileCallback;
    private SpeechRecognizer asr;
    private boolean asrPendingStart = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0C0F14);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setTextZoom(100);
        s.setUseWideViewPort(true);        // 按网页里的 viewport 设置排版，宽度=手机屏幕宽度
        s.setLoadWithOverviewMode(true);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);   // 网页都在 App 里，不用缓存：更新 App 后一定显示新内容
        web.clearCache(true);

        tts = new TextToSpeech(this, status -> ttsReady = (status == TextToSpeech.SUCCESS));
        web.addJavascriptInterface(new TtsBridge(), "AndroidTTS");
        web.addJavascriptInterface(new AsrBridge(), "AndroidASR");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open("www" + path);
                    HashMap<String, String> h = new HashMap<>();
                    h.put("Cache-Control", "no-store");
                    return new WebResourceResponse(mime(path), "utf-8", 200, "OK", h, in);
                } catch (IOException e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
                            new HashMap<>(), new ByteArrayInputStream(new byte[0]));
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("audio/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "选择报站录音"), PICK_AUDIO);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("https://" + HOST + "/index.html");
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ASR_UI) {
            ArrayList<String> r = (resultCode == RESULT_OK && data != null)
                    ? data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS) : null;
            if (r != null && !r.isEmpty()) asrEvent("final", r.get(0)); else asrEvent("end", "");
            return;
        }
        if (requestCode != PICK_AUDIO || fileCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData clip = data.getClipData();
            if (clip != null) {
                result = new Uri[clip.getItemCount()];
                for (int k = 0; k < clip.getItemCount(); k++) result[k] = clip.getItemAt(k).getUri();
            } else if (data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onAndroidBack && window.onAndroidBack()) ? '1' : '0'", v -> {
            if (!"\"1\"".equals(v)) finish();
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    // ---------------- 语音问路：语音识别 ----------------

    /** 把识别结果交给网页：window.onAsr(type, text) */
    private void asrEvent(String type, String text) {
        final String js = "window.onAsr&&window.onAsr(" + JSONObject.quote(type) + ","
                + JSONObject.quote(text == null ? "" : text) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private boolean asrServiceAvailable() {
        return SpeechRecognizer.isRecognitionAvailable(this);
    }

    private boolean asrIntentAvailable() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        return i.resolveActivity(getPackageManager()) != null;
    }

    private Intent asrIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "说出起点和终点，比如：我在体育西路，想去广州南站");
        return i;
    }

    /** 优先用后台识别（不弹窗，边说边出字）；没有的话用系统的语音输入界面 */
    private void asrStart() {
        if (asrServiceAvailable()) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                asrPendingStart = true;
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                return;
            }
            try {
                if (asr == null) {
                    asr = SpeechRecognizer.createSpeechRecognizer(this);
                    asr.setRecognitionListener(new AsrListener());
                }
                asr.startListening(asrIntent());
            } catch (Exception e) {
                asrEvent("error", "unavailable");
            }
        } else if (asrIntentAvailable()) {
            try {
                startActivityForResult(asrIntent(), REQ_ASR_UI);
            } catch (Exception e) {
                asrEvent("error", "unavailable");
            }
        } else {
            asrEvent("error", "unavailable");
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (ok && asrPendingStart) asrStart();
            else if (!ok) asrEvent("error", "denied");
            asrPendingStart = false;
        }
    }

    private class AsrListener implements RecognitionListener {
        @Override public void onReadyForSpeech(Bundle params) { asrEvent("ready", ""); }
        @Override public void onBeginningOfSpeech() { }
        @Override public void onRmsChanged(float rmsdB) { }
        @Override public void onBufferReceived(byte[] buffer) { }
        @Override public void onEndOfSpeech() { }
        @Override public void onError(int error) { asrEvent("error", String.valueOf(error)); }
        @Override public void onResults(Bundle results) {
            ArrayList<String> r = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            asrEvent("final", (r != null && !r.isEmpty()) ? r.get(0) : "");
        }
        @Override public void onPartialResults(Bundle partialResults) {
            ArrayList<String> r = partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (r != null && !r.isEmpty()) asrEvent("partial", r.get(0));
        }
        @Override public void onEvent(int eventType, Bundle params) { }
    }

    /** 给网页用的语音识别接口：window.AndroidASR */
    private class AsrBridge {
        @JavascriptInterface
        public boolean available() { return asrServiceAvailable() || asrIntentAvailable(); }

        @JavascriptInterface
        public void start() { runOnUiThread(MainActivity.this::asrStart); }

        @JavascriptInterface
        public void stop() { runOnUiThread(() -> { if (asr != null) asr.stopListening(); }); }
    }

    @Override
    protected void onDestroy() {
        if (asr != null) asr.destroy();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** 给网页用的系统语音合成接口：window.AndroidTTS */
    private class TtsBridge {
        private Locale locale(String tag) {
            Locale l = Locale.forLanguageTag(tag);
            if ("zh-HK".equalsIgnoreCase(tag) && tts.isLanguageAvailable(l) < TextToSpeech.LANG_AVAILABLE) {
                Locale yue = Locale.forLanguageTag("yue-HK");
                if (tts.isLanguageAvailable(yue) >= TextToSpeech.LANG_AVAILABLE) return yue;
            }
            return l;
        }

        @JavascriptInterface
        public boolean ready() { return ttsReady; }

        @JavascriptInterface
        public boolean isAvailable(String tag) {
            if (!ttsReady) return false;
            return tts.isLanguageAvailable(locale(tag)) >= TextToSpeech.LANG_AVAILABLE;
        }

        @JavascriptInterface
        public void speak(String text, String tag, boolean flush, float rate, float pitch) {
            if (!ttsReady || text == null) return;
            synchronized (this) {
                tts.setLanguage(locale(tag));
                tts.setSpeechRate(rate > 0 ? rate : 1f);
                tts.setPitch(pitch > 0 ? pitch : 1f);  // 小女孩/小男孩：提高音调
                tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null,
                        "u" + System.nanoTime());
            }
        }

        @JavascriptInterface
        public void stop() { if (ttsReady) tts.stop(); }

        /** 列出某种语言可用的发音人（JSON 数组），供网页里选择 */
        @JavascriptInterface
        public String voices(String tag) {
            org.json.JSONArray arr = new org.json.JSONArray();
            if (!ttsReady) return arr.toString();
            try {
                String lang = Locale.forLanguageTag(tag).getISO3Language();
                java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                if (vs != null) {
                    java.util.List<String> names = new java.util.ArrayList<>();
                    for (android.speech.tts.Voice v : vs) {
                        if (v.getLocale() != null && lang.equals(v.getLocale().getISO3Language())
                                && !v.isNetworkConnectionRequired()) names.add(v.getName());
                    }
                    java.util.Collections.sort(names);
                    for (String n : names) arr.put(n);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        /** 用指定的发音人朗读（找不到就按语言朗读） */
        @JavascriptInterface
        public void speak(String text, String tag, boolean flush, float rate, float pitch, String voiceName) {
            if (!ttsReady || text == null) return;
            synchronized (this) {
                android.speech.tts.Voice chosen = null;
                java.util.Set<android.speech.tts.Voice> vs = tts.getVoices();
                if (vs != null && voiceName != null) for (android.speech.tts.Voice v : vs) if (voiceName.equals(v.getName())) { chosen = v; break; }
                if (chosen != null) tts.setVoice(chosen); else tts.setLanguage(locale(tag));
                tts.setSpeechRate(rate > 0 ? rate : 1f);
                tts.setPitch(pitch > 0 ? pitch : 1f);
                tts.speak(text, flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, null, "u" + System.nanoTime());
            }
        }
    }
}
