package com.joanakar.technoarchive;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 301;
    private WebView webView;
    private ValueCallback<Uri[]> fileChooserCallback;
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(Color.rgb(8, 9, 13));
        getWindow().setNavigationBarColor(Color.rgb(8, 9, 13));

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setBackgroundColor(Color.rgb(8, 9, 13));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(true);

        webView.addJavascriptInterface(new AndroidBridge(), "Android");
        webView.setWebViewClient(new ArchiveWebViewClient());
        webView.setWebChromeClient(new ArchiveChromeClient());

        setContentView(webView);
        webView.loadUrl("file:///android_asset/index.html");
    }

    private void openExternal(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception ignored) {
            Toast.makeText(this, "No se pudo abrir el enlace", Toast.LENGTH_SHORT).show();
        }
    }

    private class ArchiveWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if ("file".equals(uri.getScheme())) return false;
            openExternal(uri);
            return true;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            Uri uri = Uri.parse(url);
            if ("file".equals(uri.getScheme())) return false;
            openExternal(uri);
            return true;
        }
    }

    private class ArchiveChromeClient extends WebChromeClient {
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, android.os.Message resultMsg) {
            WebView temporaryView = new WebView(MainActivity.this);
            temporaryView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    openExternal(request.getUrl());
                    return true;
                }

                @Override
                public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                    if (url != null && !"about:blank".equals(url)) openExternal(Uri.parse(url));
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(temporaryView);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileChooserCallback != null) fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = callback;
            try {
                Intent intent = params.createIntent();
                intent.setType("application/json");
                startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                return true;
            } catch (Exception error) {
                fileChooserCallback = null;
                Toast.makeText(MainActivity.this, "No se pudo abrir el selector de archivos", Toast.LENGTH_SHORT).show();
                return false;
            }
        }
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void fetchVideos(String channelId) {
            if (channelId == null || !channelId.matches("^UC[A-Za-z0-9_-]{10,30}$")) {
                sendFeedError("El identificador del canal no es válido.");
                return;
            }

            networkExecutor.execute(() -> {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL("https://www.youtube.com/feeds/videos.xml?channel_id=" + channelId);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(12000);
                    connection.setReadTimeout(12000);
                    connection.setRequestProperty("User-Agent", "Joanakar-Techno-Engine/1.0");
                    if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                        throw new IllegalStateException("YouTube no devolvió el canal.");
                    }

                    JSONArray videos = new JSONArray();
                    try (InputStream input = connection.getInputStream()) {
                        XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
                        parser.setInput(input, "UTF-8");
                        boolean inEntry = false;
                        JSONObject current = null;
                        int event = parser.getEventType();
                        while (event != XmlPullParser.END_DOCUMENT) {
                            String name = parser.getName();
                            if (event == XmlPullParser.START_TAG && "entry".equals(name)) {
                                inEntry = true;
                                current = new JSONObject();
                            } else if (event == XmlPullParser.START_TAG && inEntry && current != null) {
                                if ("title".equals(name)) {
                                    current.put("title", parser.nextText());
                                } else if ("videoId".equals(name)) {
                                    current.put("videoId", parser.nextText());
                                } else if ("link".equals(name)) {
                                    String href = parser.getAttributeValue(null, "href");
                                    if (href != null) current.put("link", href);
                                }
                            } else if (event == XmlPullParser.END_TAG && "entry".equals(name)) {
                                inEntry = false;
                                if (current != null && current.has("link")) videos.put(current);
                                current = null;
                            }
                            event = parser.next();
                        }
                    }

                    String payload = videos.toString();
                    runOnUiThread(() -> webView.evaluateJavascript(
                            "window.onFeedLoaded(" + JSONObject.quote(payload) + ")", null));
                } catch (Exception error) {
                    sendFeedError("Comprueba el identificador del canal y la conexión a Internet.");
                } finally {
                    if (connection != null) connection.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void saveImage(String dataUrl) {
            runOnUiThread(() -> {
                try {
                    String prefix = "data:image/png;base64,";
                    if (dataUrl == null || !dataUrl.startsWith(prefix)) {
                        throw new IllegalArgumentException("Imagen no válida");
                    }
                    byte[] image = Base64.decode(dataUrl.substring(prefix.length()), Base64.DEFAULT);
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Images.Media.DISPLAY_NAME, "joanakar-techno-waves.png");
                    values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                    values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Joanakar");
                    Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IllegalStateException("No se pudo crear la imagen");
                    try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IllegalStateException("No se pudo escribir la imagen");
                        output.write(image);
                    }
                    Toast.makeText(MainActivity.this, "Imagen guardada en Fotos/Joanakar", Toast.LENGTH_LONG).show();
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "No se pudo guardar la imagen", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void saveBackup(String json) {
            runOnUiThread(() -> {
                try {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, "joanakar-techno-archive.json");
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (uri == null) throw new IllegalStateException("No se pudo crear el archivo");
                    try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                        if (output == null) throw new IllegalStateException("No se pudo escribir el archivo");
                        output.write(json.getBytes(StandardCharsets.UTF_8));
                    }
                    Toast.makeText(MainActivity.this, "Copia guardada en Descargas", Toast.LENGTH_LONG).show();
                } catch (Exception error) {
                    Toast.makeText(MainActivity.this, "No se pudo guardar la copia", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void sendFeedError(String message) {
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript("window.onFeedError(" + JSONObject.quote(message) + ")", null);
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) return;
        Uri[] result = null;
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            result = new Uri[]{data.getData()};
        }
        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        networkExecutor.shutdownNow();
        if (webView != null) {
            webView.removeJavascriptInterface("Android");
            webView.destroy();
        }
        super.onDestroy();
    }
}
