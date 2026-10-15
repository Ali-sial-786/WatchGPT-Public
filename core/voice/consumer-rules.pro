# WebRTC calls back into these classes from native code.
-keep class org.webrtc.** { *; }
# Loading WebRTC's native library looks up org.jni_zero.JniZero by name and calls its init(); nothing in Java refers
# to it, so without this a release build loses the class and the first call aborts the app.
-keep class org.jni_zero.** { *; }
# The library doesn't ship the generated JniZeroJni, which only JniZero.setJniClassLoader (never called) refers to.
-dontwarn org.jni_zero.JniZeroJni
