# Keep JNI entry points and the sherpa-onnx Kotlin API (native methods resolved by name).
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.kltef.voicekeyboard.asr.WhisperRefiner { *; }
-keepclasseswithmembernames class * { native <methods>; }
