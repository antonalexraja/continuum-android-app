# Add project specific ProGuard rules here.

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep data classes used with JNI
-keep class com.navigator.core.native.** { *; }
