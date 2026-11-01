package app.grapheneos.goscompat.securespawn;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;

public final class WebViewRendererActivity extends Activity {
    private WebView mWebView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mWebView = new WebView(this);
        setContentView(mWebView);
    }

    WebView getWebView() {
        return mWebView;
    }

    @Override
    protected void onDestroy() {
        mWebView.destroy();
        super.onDestroy();
    }
}
