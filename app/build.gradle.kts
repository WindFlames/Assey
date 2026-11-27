import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// CI 打 tag 时用 -PappVersionName=1.2.3 -PappVersionCode=42 覆盖；本地构建用默认值。
val appVersionName = providers.gradleProperty("appVersionName").orNull ?: "1.0"
val appVersionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1

// 发布签名：优先读环境变量（CI），其次读仓库根目录的 keystore.properties（本地，已 gitignore）。
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(env: String, key: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(key)

android {
    namespace = "io.savant.assey"
    compileSdk {
        // Compose 1.13.0 要求 compileSdk >= 37.1，本机安装的是 android-37.2
        version = release(37) {
            minorApiLevel = 2
        }
    }

    signingConfigs {
        val storePath = signingValue("ASSEY_KEYSTORE_FILE", "storeFile")
        if (!storePath.isNullOrBlank()) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("ASSEY_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("ASSEY_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("ASSEY_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    defaultConfig {
        applicationId = "io.savant.assey"
        minSdk = 33
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // 有发布密钥就用发布密钥；没有时退回 debug 签名，保证 APK 仍可直接安装。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.findByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.openai.java)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.gson)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
