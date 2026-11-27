# ---------------------------------------------------------------------------
# Assey release 混淆规则
# ---------------------------------------------------------------------------

# 保留泛型签名、注解，Retrofit / Gson / Jackson 都依赖它们做反射解析。
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault

# Gson：字段名由 @SerializedName 决定，字段本身不能被裁掉。
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Retrofit：保留接口方法上的 HTTP 注解。
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# OpenAI 官方 SDK 内部大量使用 Jackson 注解与反射构造。
-keep class com.openai.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# 应用内通过 Gson 反序列化的传输模型。
-keep class io.savant.assey.data.parser.** { *; }
