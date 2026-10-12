# AndroidX Test tracing has this compile-time-only Error Prone annotation on
# returned spans. It is absent from Android, not queried at runtime, and this
# rule applies only to the instrumentation APK, never the app candidate.
-dontwarn com.google.errorprone.annotations.MustBeClosed
# AndroidX Test Checks also carries a compile-time-only return-value annotation.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
