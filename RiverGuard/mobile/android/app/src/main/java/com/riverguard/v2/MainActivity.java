package com.riverguard.v2;

import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

/** Only bundled local pages may run inside the privileged phone WebView. */
public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle state) {
        registerPlugin(RiverGuardV2Plugin.class);
        super.onCreate(state);
        if (bridge == null) return;
        bridge.setWebViewClient(new BridgeWebViewClient(bridge) {
            private boolean navigate(WebView view, Uri uri) {
                Uri local = Uri.parse(bridge.getLocalUrl());
                if (local.getScheme().equals(uri.getScheme()) && local.getHost().equals(uri.getHost()) && local.getPort() == uri.getPort()) return false;
                // External HTTPS links use the system browser, never this bridge.
                if ("https".equals(uri.getScheme())) return super.shouldOverrideUrlLoading(view, uri.toString());
                return true;
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return navigate(view, request.getUrl());
            }
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return navigate(view, Uri.parse(url));
            }
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                bridge.getWebView().evaluateJavascript("Boolean(window.riverguardBack && window.riverguardBack())", consumed -> {
                    if (!"true".equals(consumed)) finish();
                });
            }
        });
    }
}
