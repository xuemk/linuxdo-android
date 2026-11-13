plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "org.linuxdo.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.linuxdo.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 18
        versionName = "1.6.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // DoH (DNS over HTTPS) 支持：纯 Kotlin 实现，无本地库，增量 < 30KB
    implementation("com.squareup.okhttp3:okhttp-dnsoverhttps:4.12.0")

    val media3Version = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 头像与帖内图片:必须复用带固定 UA + Cookie 的 OkHttpClient,否则图片会被 Cloudflare 拦。
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("io.coil-kt:coil-gif:2.7.0")
    // 解析 Discourse 的 cooked HTML 为块模型;纯 Java,无传递依赖。
    implementation("org.jsoup:jsoup:1.18.1")
    // 后台定期任务(低频只读静默探测未读通知)
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // WebView 代理导流:ProxyController 只存在于该库,framework 的 WebView 没有公开代理 API。
    // zero-doh-local-proxy-tunnel 方案的 WebView 侧接入(task 3.1)强依赖它。
    implementation("androidx.webkit:webkit:1.12.1")

    // GeckoView: Firefox 内核网络栈与渲染引擎 (含 DoH/TRR + TLS 1.3 ECH + 官方 NSS 指纹)
    implementation("org.mozilla.geckoview:geckoview-arm64-v8a:135.0.20250130195129")
}
