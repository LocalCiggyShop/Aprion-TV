# Retain the explicit security boundary, not every desktop Rhino optimizer/tool.
# The only other admitted Rhino use is the extractor's token parser (Kit and
# ScriptRuntime); R8 follows those ordinary static references.
-keep class org.schabi.newpipe.extractor.utils.JavaScript { public static *; }
# AndroidJUnitRunner uses this shared AndroidX API when testing the minified
# candidate. Keep this single facade so cross-APK tracing calls still resolve.
-keep class androidx.tracing.Trace { public static *; }
