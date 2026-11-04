package com.sednium.kryptonide.ui.monaco

import android.webkit.JavascriptInterface

/**
 * JavaScript interface attached to `window.KryptonNative` inside the headless Monaco runtime.
 */
class MonacoBridge(
    private val listener: MonacoEventListener
) {
    interface MonacoEventListener {
        fun onConsoleLog(level: String, message: String)
        fun onThemeDefined(name: String, themeJson: String)
        fun onThemeChanged(name: String)
        fun onCompletionResult(requestId: String, itemsJson: String)
        fun onApplyEdits(editsJson: String)
        fun onMarkers(owner: String, markersJson: String)
        fun onActionRegistered(actionId: String, label: String)
    }

    @JavascriptInterface
    fun postConsoleLog(level: String, message: String) {
        listener.onConsoleLog(level, message)
    }

    @JavascriptInterface
    fun postThemeDefined(themeName: String, themeJson: String) {
        listener.onThemeDefined(themeName, themeJson)
    }

    @JavascriptInterface
    fun postThemeChanged(themeName: String) {
        listener.onThemeChanged(themeName)
    }

    @JavascriptInterface
    fun postCompletionResult(requestId: String, itemsJson: String) {
        listener.onCompletionResult(requestId, itemsJson)
    }

    @JavascriptInterface
    fun postApplyEdits(editsJson: String) {
        listener.onApplyEdits(editsJson)
    }

    @JavascriptInterface
    fun postMarkers(owner: String, markersJson: String) {
        listener.onMarkers(owner, markersJson)
    }

    @JavascriptInterface
    fun postActionRegistered(actionId: String, label: String) {
        listener.onActionRegistered(actionId, label)
    }
}
