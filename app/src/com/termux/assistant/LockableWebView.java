package com.termux.assistant;

import android.content.Context;
import android.util.AttributeSet;
import android.webkit.WebView;

public class LockableWebView extends WebView {
    private boolean keyboardLocked = false;

    public LockableWebView(Context c) { super(c); }
    public LockableWebView(Context c, AttributeSet a) { super(c, a); }
    public LockableWebView(Context c, AttributeSet a, int d) { super(c, a, d); }

    public void setKeyboardLocked(boolean locked) { this.keyboardLocked = locked; }
    public boolean isKeyboardLocked() { return keyboardLocked; }

    @Override
    public boolean onCheckIsTextEditor() {
        if (keyboardLocked) return false;
        return super.onCheckIsTextEditor();
    }
}
