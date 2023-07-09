-keepattributes Signature

-keepclassmembers class eu.magicsk.transi.** {
    public *;
    protected *;
}
-keep class eu.magicsk.transi.data.** {*; }
-keep class com.google.android.gms.** { *; }
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

# Enable Optimization. # Optimization is turned off by default.
-optimizations   code/simplification/arithmetic,!code/simplification/cast,!field
-optimizationpasses 5
-allowaccessmodification


# Remove Log command from code
-assumenosideeffects class android.util.Log{
 public static *** d(...);
 public static *** i(...);
 public static *** v(...);
}

-keepattributes *Annotation*, Signature, Exception, EnclosingMethod, InnerClasses
-keepattributes JavascriptInterface

# Support Library
-keep class android.support.** {*;}
-keep interface android.support.** {*;}


# Needed when building against Marshmallow SDK.
  -dontwarn android.app.Notification


# Retrofit and GSON
-keep class com.squareup.okhttp3.** { *; }
-keep interface com.squareup.okhttp3.** { *; }
-dontwarn com.squareup.okhttp3.**

-keep class retrofit2.** { *; }
-dontwarn retrofit2.**

-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

-keep class sun.misc.Unsafe.** { *; }
-dontwarn sun.misc.Unsafe.**

-keep public class com.google.gson.** {*;}
-keep class * implements com.google.gson.** {*;}
-keep class com.google.gson.stream.** { *; }
-dontwarn com.google.gson.**

-keepclasseswithmembers class * {@retrofit2.http.* <methods>;}
-keepclasseswithmembers interface * { @retrofit2.* <methods>;}
-dontwarn com.google.appengine.**
-dontwarn java.nio.file.**
-dontwarn org.codehaus.**
-dontwarn org.codehaus.mojo.**
-dontnote retrofit2.Platform

# Firebase
-keep class com.firebase.** { *; }
-dontwarn com.firebase.**

-keepnames class com.shaded.fasterxml.** { *; }
-dontwarn org.shaded.apache.**

-keep class org.apache.** { *; }
-dontwarn org.apache.**

-keepnames class org.ietf.jgss.** { *; }
-dontwarn org.ietf.jgss.**

-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.**

-keepnames class javax.servlet.** { *; }

-dontwarn org.w3c.dom.**
-dontwarn org.joda.time.**


-keepclassmembers class android.arch.** { *; }
-keep class android.arch.** { *; }
-dontwarn android.arch.**


#Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
  **[] $VALUES;
  public *;
}
-dontwarn com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
-dontwarn com.bumptech.glide.load.resource.bitmap.Downsampler
-dontwarn com.bumptech.glide.load.resource.bitmap.HardwareConfigState

-keep class com.google.**
-dontwarn com.google.**

-keep class org.ocpsoft.prettytime.** {*;}
-dontwarn org.ocpsoft.prettytime.**

-keep class androidx.lifecycle.** { *; }

-ignorewarnings
-keep class * {
    public private *;
}

-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

-dontwarn org.xmlpull.v1.**
-dontnote org.xmlpull.v1.**
-keep class org.xmlpull.** { *; }

#retRofit
# Platform calls Class.forName on types which do not exist on Android to determine platform.
-dontnote retrofit2.Platform
# Retain generic type information for use by reflection by converters and adapters.
-keepattributes Signature
# Retain declared checked exceptions for use by a Proxy instance.
-keepattributes Exceptions
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

-keepclassmembers class * {
    native <methods>;
}
-dontwarn okio.**
-dontwarn com.squareup.okhttp.**
-dontwarn okhttp3.**
-dontwarn javax.annotation.**
-dontwarn com.android.volley.toolbox.**

-keep class dagger.** { *; }
-keep class * extends dagger.** { *; }
-keep interface dagger.** {*;}
-dontwarn dagger.internal.codegen.**

-keep class * extends dagger.hilt.*