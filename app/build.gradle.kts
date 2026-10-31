import com.google.protobuf.gradle.proto

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("kotlin-parcelize")
    id("com.mikepenz.aboutlibraries.plugin")
    id("androidx.room")
    id("com.google.protobuf")
}

android {
    // fork: 保持 namespace 不变。namespace 与 Kotlin 包路径、R 引用绑定，改名需触及
    // 29k 行源码与全部 import；如需彻底改名，另开一次纯机械的重命名提交。
    namespace = "ua.acclorite.book_story"
    compileSdk = 36

    // Default configuration
    defaultConfig {
        // fork: 与上游区分，避免覆盖已安装的 Book's Story。
        // 改动此值时必须同步 app/src/main/AndroidManifest.xml 中的三个
        // DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION 名称，否则 tools:node="remove" 会失效。
        applicationId = "dev.jia.reader"
        // fork: 上游为 26。提到 29 的硬理由只有一条：
        //   Typeface.CustomFallbackBuilder（API 29）—— 用户导入字体时唯一能串中文字形回退链的 API。
        // 注意 NOT 理由：避头尾与 LineBreak 无关（实测 ICU 无条件遵守 UAX#14）。
        // LineBreak 的 strictness / wordBreak 部分依赖 android.graphics.text.LineBreakConfig（API 33），
        // 在 29~32 上会退化；但断行策略（HIGH_QUALITY）从 API 23 就有。
        // rebase upstream 后务必检查此行，冲突不会导致编译失败，只会静默退回 26。
        // fork: rebase-critical — 见 REBASE.md 第 1 节
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "1.8.0"

        vectorDrawables {
            useSupportLibrary = true
        }

        // fork: 上游未配置，默认 runner 是已废弃的 android.test.InstrumentationTestRunner，
        // 导致 AndroidJUnit4 无法使用。排版自检（androidTest）依赖此项。
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Build types configuration
    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }

        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = false

            proguardFiles("proguard-rules.pro")
        }

        create("release-debug") {
            initWith(getByName("release"))
            applicationIdSuffix = ".release.debug"
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Kotlin configuration
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Source sets
    sourceSets {
        named("main") {
            proto {
                srcDir("src/main/java/ua/acclorite/book_story/data/proto")
            }
        }

        // fork: Room 把 schema 导出到 app/schemas，但不会自动塞进仪器测试 APK，
        // `MigrationTestHelper` 是在测试进程里按 assets 路径读这两份 JSON 的（第二阶段 S7）。
        // 不加这一行，迁移测试会在运行时抛 "Cannot locate schema file"。
        named("androidTest") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    // Room configuration
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    room {
        schemaDirectory("$projectDir/schemas")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    // fork: JVM 单测跑在 android.jar 的桩上，未实现的 android.* 方法默认抛
    // "Method ... not mocked"。S5 的取词状态机在错误路径里会 logW（android.util.Log），
    // 不开这个开关就只能测成功路径。只影响单测，不影响 App 行为。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/gradle/incremental.annotation.processors"
        }
    }
}

// About Libraries configuration
aboutLibraries {
    registerAndroidTasks = false
    prettyPrint = true

    filterVariants = arrayOf("debug", "release", "release-debug")
    excludeFields = arrayOf("generated", "funding", "description")
}

// Protobuf configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.33.2"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.activity:activity-compose:1.11.0")

    // WebView compatibility (fork: phase 2, dictionary article rendering)
    // 词条里的图片/CSS/表格交给 WebView（设计 §3.6）；WebSettingsCompat 的
    // setAlgorithmicDarkeningAllowed 只有 androidx.webkit 提供，裸 WebSettings 没有对应 API。
    implementation("androidx.webkit:webkit:1.14.0")

    // Compose BOM libraries
    // Compose BOM was eliminated - it is recognized as Closed Source in AboutLibraries..
    // although it is not.
    implementation("androidx.compose.foundation:foundation:1.9.3")
    implementation("androidx.compose.animation:animation:1.9.3")
    implementation("androidx.compose.animation:animation-android:1.9.3")
    implementation("androidx.compose.foundation:foundation-layout:1.9.3")
    implementation("androidx.compose.ui:ui:1.9.3")
    implementation("androidx.compose.ui:ui-graphics:1.9.3")
    implementation("androidx.compose.ui:ui-android:1.9.3")
    implementation("androidx.compose.material3:material3:1.5.0-alpha06")
    implementation("androidx.compose.material3:material3-window-size-class:1.4.0")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.material:material:1.9.3")

    // All dependencies
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("com.google.accompanist:accompanist-swiperefresh:0.36.0")

    // Dagger - Hilt
    implementation("com.google.dagger:hilt-android:2.57.2")
    ksp("com.google.dagger:hilt-android-compiler:2.57.2")
    implementation("com.google.dagger:hilt-compiler:2.57.2")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Room
    implementation("androidx.room:room-runtime:2.7.2")
    ksp("androidx.room:room-compiler:2.7.2")

    // Kotlin Extensions and Coroutines support for Room
    implementation("androidx.room:room-ktx:2.7.2")

    // Datastore (Settings)
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    // Splash Screen API
    implementation("androidx.core:core-splashscreen:1.0.1")

    // SAF
    implementation("com.anggrayudi:storage:2.2.0")

    // PDF parser
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // EPUB parser
    implementation("org.jsoup:jsoup:1.21.2")

    // OpenAI compatible API client (fork: phase 2 AI translation)
    // openai-client 4.1.0 编译期就依赖 Ktor 3.0.0，二者版本必须一起走；
    // 4.1.0 的 OpenAIConfig 有 engine 参数，MockEngine 测试才能离线。
    implementation("com.aallam.openai:openai-client:4.1.0")
    implementation("io.ktor:ktor-client-okhttp:3.0.0")

    // API key encryption (fork: phase 2, AndroidKeysetManager + AES256-GCM)
    implementation("com.google.crypto.tink:tink-android:1.18.0")

    // 7z decompression for the built-in ECDICT (fork: phase 2)
    // commons-compress 的 7z LZMA2 解码器通过反射探测 org.tukaani.xz，缺了它整份 7z 读不了。
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")

    // Dictionary engine (fork: phase 2, vendored GPL-3.0 juicedict engine)
    // 纯 JVM 模块（third_party/juicedict-engine），S3 起由词典仓库实际调用；
    // 现在先接上依赖，保证模块与 app 一起参与构建/测试。
    implementation(project(":dict-engine"))

    // Language Switcher
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.appcompat:appcompat-resources:1.7.1")

    // Coil for loading images
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Open source libraries
    implementation("com.mikepenz:aboutlibraries-core:11.4.0")
    implementation("com.mikepenz:aboutlibraries-compose-m3:11.4.0")

    // Drag & Drop
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // Scrollbar
    implementation("com.github.nanihadesuka:LazyColumnScrollbar:2.2.0")

    // Markdown
    implementation("org.commonmark:commonmark:0.26.0")

    // Json
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Protobuf
    implementation("com.google.protobuf:protobuf-javalite:4.33.2")
    implementation("com.google.protobuf:protobuf-kotlin-lite:4.33.2")

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("com.google.truth.extensions:truth-proto-extension:1.4.5")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.3.0")
    // fork: 与 openai-client 的 Ktor 版本对齐，供流式 spike 测试离线注入
    testImplementation("io.ktor:ktor-client-mock:3.0.0")

    // UI testing
    // fork: 上游将三个测试件钉在 1.10.0，而 app 本身用 1.9.3 —— Compose 是 atomic group，
    // debug/androidTest 变体因此整体解析到 ui:1.10.0，导致「调试包与发布包跑的不是同一套文本引擎」。
    // 排版自检只用 runner + junit4，不依赖 Compose UI 测试（Round 2 评审：留着它们就还是会被后续
    // 版本 bump 重新拉上去，所以直接不引入）。现在 debug 与 release 解析到同一个 1.9.3。
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // fork: MigrationTestHelper（Room 20→21 迁移测试，第二阶段 S7）
    androidTestImplementation("androidx.room:room-testing:2.7.2")
    // fork: @Preview 需要 ui-tooling；上游钉在 1.10.0，会把 debug 变体的 ui 拉到 1.10.0，已钉回 1.9.3
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.3")
    kspAndroidTest("com.google.dagger:hilt-android-compiler:2.57.2")
    testImplementation(kotlin("test"))
}