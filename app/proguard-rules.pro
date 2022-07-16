-keepclassmembers class eu.magicsk.transi.TimetableFragment {
   public *;
}
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn 'org.conscrypt.Conscrypt$Version'
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-keep class eu.magicsk.transi.data.models.** {*; }
-keep class eu.magicsk.transi.data.remote.responses.** {*; }
-keepattributes SourceFile,LineNumberTable