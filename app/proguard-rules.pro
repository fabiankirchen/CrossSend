# CrossSend ProGuard / R8 Rules

# Keep WebView JavascriptInterface methods (ScopedPluginJsBridge)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface,*Annotation*

# Keep CrossSend data/network/plugin models
-keep class com.crosssend.app.data.local.** { *; }
-keep class com.crosssend.app.network.** { *; }
-keep class com.crosssend.app.ui.screens.SdPluginInfo { *; }
-keep class com.crosssend.app.ui.screens.StorePluginItem { *; }
-keep class com.crosssend.app.ui.screens.ScopedPluginJsBridge { *; }
-keep class com.crosssend.app.converter.EInkImageOptimizer$Companion$CrossPointHardwareProfile { *; }

# Room Database
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.paging.**

# Jsoup & Readability4J
-keeppackagenames org.jsoup.nodes
-dontwarn org.jsoup.**
-dontwarn net.dankito.readability4j.**

# PdfBox-Android & BouncyCastle
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
