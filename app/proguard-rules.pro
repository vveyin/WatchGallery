# Add project specific ProGuard rules here.
-keepattributes *Annotation*
-dontwarn kotlin.**
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
}
