# Java Lite MessageSchema reflects the generated field names encoded in each
# message's schema. Renaming/removing them broke YouTube XTags parsing in R8
# builds (NoSuchFieldException). Keep fields only for the extractor's messages.
# Upstream rule: https://github.com/protocolbuffers/protobuf/blob/main/java/lite/proguard.pgcfg
-keepclassmembers class org.schabi.newpipe.extractor.services.youtube.protos.** extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
