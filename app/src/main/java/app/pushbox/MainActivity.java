package app.pushbox;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final int REQ_FILES = 1001;
    private static final int REQ_TREE = 1002;
    private static final int REQ_UNLOCK = 1003;

    private WebView web;
    private ValueCallback<Uri[]> pendingChooser;
    private TokenStore tokens;
    private boolean pageReady = false;
    private String pendingShare = null;

    // Files and folders the user picked or shared. The page reads them from /shared/<id>.
    private final ConcurrentHashMap<Integer, Uri> shared = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        tokens = new TokenStore(this);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        // Serves the bundled page from https://appassets.androidplatform.net/ so it runs in a
        // secure context (needed for fast hashing) and can call api.github.com.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/shared/", path -> {
                    try {
                        Uri uri = shared.get(Integer.parseInt(path.split("[/?]")[0]));
                        if (uri == null) return null;
                        InputStream in = getContentResolver().openInputStream(uri);
                        return new WebResourceResponse("application/octet-stream", null, in);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equals(u.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, u));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                flushShare();
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingChooser != null) pendingChooser.onReceiveValue(null);
                pendingChooser = callback;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                try {
                    startActivityForResult(Intent.createChooser(pick, "Choose files"), REQ_FILES);
                } catch (ActivityNotFoundException e) {
                    pendingChooser = null;
                    return false;
                }
                return true;
            }
        });

        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("https://" + HOST + "/assets/index.html");
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    // ---- sharing a ZIP or files into Pushbox ----

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        ArrayList<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) uris.addAll(list);
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            uris.add(intent.getData());
        }
        if (uris.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (Uri u : uris) {
                int id = nextId.getAndIncrement();
                shared.put(id, u);
                JSONObject o = new JSONObject();
                o.put("name", queryString(u, OpenableColumns.DISPLAY_NAME, "file"));
                o.put("size", queryLong(u, OpenableColumns.SIZE));
                o.put("url", "/shared/" + id);
                arr.put(o);
            }
            pendingShare = arr.toString();
            flushShare();
        } catch (Exception ignored) {
        }
    }

    private void flushShare() {
        if (!pageReady || pendingShare == null) return;
        String js = "window.pushboxShared&&window.pushboxShared(" + pendingShare + ")";
        pendingShare = null;
        web.evaluateJavascript(js, null);
    }

    private String queryString(Uri uri, String column, String fallback) {
        try (Cursor c = getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private long queryLong(Uri uri, String column) {
        try (Cursor c = getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Exception ignored) {
        }
        return 0;
    }

    // ---- picking a whole folder ----

    private void scanTree(final Uri tree) {
        runJs("window.pushboxFolderBusy&&window.pushboxFolderBusy()");
        new Thread(() -> {
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                String name = queryString(DocumentsContract.buildDocumentUriUsingTree(tree, rootId),
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, "folder");
                JSONArray files = new JSONArray();
                int[] skipped = {0};
                walk(tree, rootId, "", files, skipped, 0);
                JSONObject result = new JSONObject();
                result.put("name", name);
                result.put("files", files);
                result.put("skipped", skipped[0]);
                runJs("window.pushboxFolder&&window.pushboxFolder(" + result + ")");
            } catch (Exception e) {
                runJs("window.pushboxFolderError&&window.pushboxFolderError(" + JSONObject.quote(String.valueOf(e.getMessage())) + ")");
            }
        }).start();
    }

    private void walk(Uri tree, String docId, String prefix, JSONArray out, int[] skipped, int depth) throws Exception {
        if (depth > 30) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor c = getContentResolver().query(children, cols, null, null, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String name = c.getString(1);
                String mime = c.getString(2);
                long size = c.isNull(3) ? 0 : c.getLong(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (".git".equals(name) || "node_modules".equals(name)) {
                        skipped[0]++;
                        continue;
                    }
                    walk(tree, id, prefix + name + "/", out, skipped, depth + 1);
                } else {
                    int key = nextId.getAndIncrement();
                    shared.put(key, DocumentsContract.buildDocumentUriUsingTree(tree, id));
                    JSONObject f = new JSONObject();
                    f.put("path", prefix + name);
                    f.put("size", size);
                    f.put("url", "/shared/" + key);
                    out.put(f);
                }
            }
        }
    }

    // ---- activity results ----

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_FILES) {
            if (pendingChooser != null) {
                pendingChooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
                pendingChooser = null;
            }
            return;
        }
        if (requestCode == REQ_TREE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                scanTree(data.getData());
            } else {
                runJs("window.pushboxFolder&&window.pushboxFolder(null)");
            }
            return;
        }
        if (requestCode == REQ_UNLOCK) {
            deliverToken(resultCode == RESULT_OK ? tokens.load() : "");
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void deliverToken(String token) {
        runJs("window.pushboxToken&&window.pushboxToken(" + JSONObject.quote(token) + ")");
    }

    private void runJs(final String js) {
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }

    /** What the page can ask the app to do. Methods run on a background thread. */
    private final class Bridge {
        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                o.put("versionCode", BuildConfig.VERSION_CODE);
                o.put("repo", BuildConfig.REPO);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        @JavascriptInterface
        public void pickFolder() {
            runOnUiThread(() -> {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_TREE);
                } catch (ActivityNotFoundException e) {
                    runJs("window.pushboxFolderError&&window.pushboxFolderError('No folder picker is available on this phone.')");
                }
            });
        }

        @JavascriptInterface
        public void saveToken(String token) {
            if (token != null && !token.isEmpty()) tokens.save(token);
        }

        @JavascriptInterface
        public void clearToken() {
            tokens.clear();
        }

        @JavascriptInterface
        public boolean isLocked() {
            return tokens.isLocked();
        }

        @JavascriptInterface
        public void setLock(boolean locked) {
            tokens.setLocked(locked);
        }

        @JavascriptInterface
        public void requestToken() {
            if (!tokens.hasToken()) return;
            if (!tokens.isLocked()) {
                deliverToken(tokens.load());
                return;
            }
            runOnUiThread(() -> {
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                Intent confirm = (km != null && km.isDeviceSecure())
                        ? km.createConfirmDeviceCredentialIntent("Unlock Pushbox", "Confirm to use your saved GitHub token")
                        : null;
                if (confirm == null) {
                    deliverToken(tokens.load());
                } else {
                    startActivityForResult(confirm, REQ_UNLOCK);
                }
            });
        }
    }
}
