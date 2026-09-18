# Keep JavascriptInterface annotated methods (WebView bridge)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
