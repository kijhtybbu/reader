# Proguard-rules
-dontwarn android.content.res.**
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn com.google.auto.service.AutoService
-dontwarn javax.lang.model.SourceVersion
-dontwarn javax.lang.model.element.AnnotationMirror
-dontwarn javax.lang.model.element.AnnotationValue
-dontwarn javax.lang.model.element.Element
-dontwarn javax.lang.model.element.ElementKind
-dontwarn javax.lang.model.element.ElementVisitor
-dontwarn javax.lang.model.element.ExecutableElement
-dontwarn javax.lang.model.element.Modifier
-dontwarn javax.lang.model.element.Name
-dontwarn javax.lang.model.element.PackageElement
-dontwarn javax.lang.model.element.TypeElement
-dontwarn javax.lang.model.element.TypeParameterElement
-dontwarn javax.lang.model.element.VariableElement
-dontwarn javax.lang.model.type.ArrayType
-dontwarn javax.lang.model.type.DeclaredType
-dontwarn javax.lang.model.type.ExecutableType
-dontwarn javax.lang.model.type.TypeKind
-dontwarn javax.lang.model.type.TypeMirror
-dontwarn javax.lang.model.type.TypeVariable
-dontwarn javax.lang.model.type.TypeVisitor
-dontwarn javax.lang.model.util.AbstractAnnotationValueVisitor8
-dontwarn javax.lang.model.util.AbstractTypeVisitor8
-dontwarn javax.lang.model.util.ElementFilter
-dontwarn javax.lang.model.util.Elements
-dontwarn javax.lang.model.util.SimpleElementVisitor8
-dontwarn javax.lang.model.util.SimpleTypeVisitor7
-dontwarn javax.lang.model.util.SimpleTypeVisitor8
-dontwarn javax.lang.model.util.Types
-dontwarn javax.tools.Diagnostic$Kind
-dontwarn org.kxml2.io.KXml**
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
-dontwarn org.xmlpull.v1.XmlPullParserFactory

# Fixes issue with displaying .xml files, but may produce reproducibility issues
#-keep class org.xmlpull.** { *; }
#-keepclassmembers class org.xmlpull.** { *; }

# Keeping names of classes that produce undeterministic results
-keepnames class **
-keepnames class org.xmlpull.** { *; }
-keepclassmembernames class org.xmlpull.** { *; }
-keepnames class kotlin.reflect.jvm.internal.impl.builtins.PrimitiveType { values(); }
-keepnames class * implements android.os.Parcelable { ** CREATOR; }
# fork: protobuf-javalite 用反射按**字段名**读写消息（GeneratedMessageLite / ReflectionHelper），
# 而它自身不带 consumer proguard 规则。R8 把 `text_` 改名成 `e` 之后，
# NodeContentListProto.parseFrom 抛 "Field text_ for ParagraphProto not found"，
# 被 cacheNodes 的 runCatching 吞掉 → 解析器以为没有节点 → 报「文件似乎为空」。
# 已用四组对照确认：官方发布版 v1.8.0（无 protobuf）正常；未改动的 upstream/master 的
# release-debug 失败、debug 正常；加上本规则后 minified 构建正常。
# 即：这是 upstream/master 上一个尚未发布的回归（protobuf 是在 v1.8.0 之后引入的）。
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}

# fork: commons-compress 的 7z 解压当前是「直接 new 解码器」：`Coders` 里是静态构造的
# 解码器表（已用 release-debug 的 classes.dex/mapping 确认 LZMA/LZMA2/BCJ 等都在包里）。
# 这里仍然整包 keep，属于**防御性**规则：commons-compress 自身不带 consumer proguard 规则，
# 若后续版本改成按类名解析解码器，`-keepnames class **` 只保名字不保构造器，会在 minified
# 包里抛 InstantiationException/NoSuchMethodException。代价约 +164KB（PROGRESS.md §3.3）；
# 真正的验证仍是 S9 在 release-debug 设备上跑一次首次解压。
-keep class org.apache.commons.compress.archivers.sevenz.** { *; }
-keep class org.tukaani.xz.** { *; }
-dontwarn org.tukaani.xz.**
