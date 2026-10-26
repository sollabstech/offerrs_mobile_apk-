package com.sollabstech.offerss;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.window.OnBackInvokedDispatcher;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URISyntaxException;

public class MainActivity extends Activity {

    private static final int FILE_CHOOSER_REQUEST = 1001;

    /** Hosts that stay inside the app; any other http(s) link opens in the phone's browser. */
    private static final String[] IN_APP_HOSTS = {
            "theofferss.com", "razorpay.com", "firebaseapp.com", "googleapis.com", "gstatic.com",
    };

    private WebView webView;
    private ProgressBar progressBar;
    private View offlineView;
    private ValueCallback<Uri[]> filePathCallback;
    private boolean mainFrameFailed;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        webView = new WebView(this);
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.orange)));
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3));
        barParams.gravity = Gravity.TOP;
        root.addView(progressBar, barParams);

        offlineView = buildOfflineView();
        offlineView.setVisibility(View.GONE);
        root.addView(offlineView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString() + " OfferssApp/" + BuildConfig.VERSION_NAME);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }

        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            loadHome();
        }
    }

    private void loadHome() {
        if (!isOnline()) {
            showOffline(true);
            return;
        }
        showOffline(false);
        Uri data = getIntent() != null ? getIntent().getData() : null;
        webView.loadUrl(data != null && isInAppHost(data) ? data.toString() : getString(R.string.home_url));
    }

    private boolean isInAppHost(Uri uri) {
        String host = uri.getHost();
        if (host == null) return false;
        for (String h : IN_APP_HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }

    /** Firebase's Google sign-in popup/redirect — Google refuses to show its login page inside app web views. */
    private boolean isGoogleSignIn(Uri uri) {
        String host = uri.getHost() == null ? "" : uri.getHost();
        String path = uri.getPath() == null ? "" : uri.getPath();
        return host.equals("accounts.google.com") || path.startsWith("/__/auth/");
    }

    /** Returns true if the URL was handled outside the web view. */
    private boolean handleExternally(Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();

        if (scheme.equals("http") || scheme.equals("https")) {
            if (isGoogleSignIn(uri)) {
                showGoogleLoginMessage();
                return true;
            }
            if (isInAppHost(uri)) return false;
            return openExternal(new Intent(Intent.ACTION_VIEW, uri));
        }

        if (scheme.equals("intent")) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                intent.addCategory(Intent.CATEGORY_BROWSABLE);
                intent.setComponent(null);
                intent.setSelector(null);
                try {
                    startActivity(intent);
                } catch (ActivityNotFoundException e) {
                    String fallback = intent.getStringExtra("browser_fallback_url");
                    if (fallback != null) webView.loadUrl(fallback);
                    else toast(R.string.no_app_found);
                }
            } catch (URISyntaxException e) {
                toast(R.string.no_app_found);
            }
            return true;
        }

        // tel:, mailto:, whatsapp:, upi:, sms:, market: … hand off to the matching app
        return openExternal(new Intent(Intent.ACTION_VIEW, uri));
    }

    private boolean openExternal(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(R.string.no_app_found);
        }
        return true;
    }

    private void showGoogleLoginMessage() {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(R.string.google_login_title)
                .setMessage(R.string.google_login_message)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private class AppWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleExternally(request.getUrl());
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            mainFrameFailed = false;
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
            if (!mainFrameFailed) showOffline(false);
            CookieManager.getInstance().flush();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) {
                mainFrameFailed = true;
                showOffline(true);
            }
        }
    }

    private class AppChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            if (newProgress >= 100) progressBar.setVisibility(View.GONE);
        }

        /** window.open(): decide from the first URL the popup tries to load. */
        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            WebView popup = new WebView(MainActivity.this);
            popup.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    if (!handleExternally(uri)) webView.loadUrl(uri.toString());
                    v.destroy();
                    return true;
                }
            });
            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (filePathCallback != null) filePathCallback.onReceiveValue(null);
            filePathCallback = callback;
            try {
                startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
            } catch (ActivityNotFoundException e) {
                filePathCallback = null;
                toast(R.string.no_app_found);
                return false;
            }
            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            filePathCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri data = intent.getData();
        if (data != null && isInAppHost(data)) webView.loadUrl(data.toString());
    }

    /** Back goes to the previous page; on the first page it leaves the app. */
    private void handleBack() {
        if (offlineView.getVisibility() != View.VISIBLE && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        webView.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onDestroy() {
        webView.destroy();
        super.onDestroy();
    }

    private void showOffline(boolean show) {
        offlineView.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) progressBar.setVisibility(View.GONE);
    }

    @SuppressWarnings("deprecation")
    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkInfo info = cm == null ? null : cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private View buildOfflineView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(Color.WHITE);
        box.setPadding(dp(32), dp(32), dp(32), dp(32));
        box.setClickable(true);

        TextView title = new TextView(this);
        title.setText(R.string.offline_title);
        title.setTextSize(20);
        title.setTextColor(getColor(R.color.navy));
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText(R.string.offline_message);
        msg.setTextSize(15);
        msg.setTextColor(Color.parseColor("#5C6673"));
        msg.setGravity(Gravity.CENTER);
        msg.setPadding(0, dp(8), 0, dp(24));
        box.addView(msg);

        Button retry = new Button(this);
        retry.setText(R.string.retry);
        retry.setTextColor(Color.WHITE);
        retry.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.orange)));
        retry.setOnClickListener(v -> {
            if (!isOnline()) {
                toast(R.string.offline_message);
                return;
            }
            showOffline(false);
            if (webView.getUrl() == null) loadHome();
            else webView.reload();
        });
        box.addView(retry, new LinearLayout.LayoutParams(dp(180), ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
