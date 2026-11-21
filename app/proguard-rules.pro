# ZarkTV: R8 ligado só para otimizar (Compose fica bem mais rápido em TV Box fraca).
# Sem ofuscar nomes, e mantendo inteiro o que usa reflexão/código nativo.
-dontobfuscate

# Código do app: pode otimizar, mas nada é removido nem renomeado.
-keep,allowoptimization class io.github.axon07.channelbox.** { *; }

# Bibliotecas com reflexão ou JNI.
-keep class com.wireguard.** { *; }
-keep class com.hierynomus.** { *; }
-keep class net.engio.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keep class androidx.media3.datasource.rtmp.** { *; }
-keep class net.butterflytv.** { *; }
-keep class io.antmedia.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.zxing.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

-dontwarn **
