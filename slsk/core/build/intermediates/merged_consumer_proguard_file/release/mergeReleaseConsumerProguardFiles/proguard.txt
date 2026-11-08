# :slsk:core consumer rules.
# OkHttp / Retrofit are already shipped by the host app; keep gateway DTOs intact
# for kotlinx.serialization (they rely on constructor reflection only, which R8
# keeps via -keepattributes below).
-keepattributes *Annotation*, InnerClasses, Signature
-keep class com.github.pandeiro.tempore.slsk.core.api.** { *; }
-keep class com.github.pandeiro.tempore.slsk.core.domain.** { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
