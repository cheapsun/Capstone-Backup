// app/build.gradle.kts  —— Kotlin DSL

import java.util.Properties

// 1) local.properties 읽기
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    // ★ BuildConfig가 생성될 패키지(= 앱 패키지). 코드의 import와 반드시 일치해야 합니다.
    namespace = "com.example.project_2"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.project_2"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        // 2) BuildConfig 상수 주입 (문자열은 큰따옴표로 한번 더 감싸야 합니다)
        buildConfigField(
            type = "String",
            name = "OPENAI_API_KEY",
            value = "\"${localProps.getProperty("OPENAI_API_KEY", "")}\""
        )
        buildConfigField(
            type = "String",
            name = "OPENWEATHER_API_KEY",
            value = "\"${localProps.getProperty("OPENWEATHER_API_KEY", "")}\""
        )
        buildConfigField(
            type = "String",
            name = "KAKAO_NATIVE_APP_KEY",
            value = "\"${localProps.getProperty("KAKAO_NATIVE_APP_KEY", "")}\""
        )

        buildConfigField(
            type = "String",
            name = "KAKAO_REST_API_KEY",
            value = "\"${localProps.getProperty("KAKAO_REST_API_KEY", "")}\""
        )

        buildConfigField(
            type = "String",
            name = "TMAP_API_KEY",
            value = "\"${localProps.getProperty("TMAP_API_KEY", "")}\""
        )
        buildConfigField(
            type = "String",
            name = "NAVER_CLIENT_ID",
            value = "\"${localProps.getProperty("NAVER_CLIENT_ID", "")}\""
        )
        buildConfigField(
            type = "String",
            name = "NAVER_CLIENT_SECRET",
            value = "\"${localProps.getProperty("NAVER_CLIENT_SECRET", "")}\""
        )

        vectorDrawables { useSupportLibrary = true }
    }

    // 3) Compose & BuildConfig 생성 켜기 (defaultConfig 바깥)
    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Compose BOM
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    // Material Icons Extended (for MyLocation icon)
    implementation("androidx.compose.material:material-icons-extended:1.7.5")

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // Navigation Compose (for bottom navigation bar)
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Coroutines Play Services (for tasks.await())
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Kakao Map SDK
    implementation("com.kakao.maps.open:android:2.9.5")

    // Google Play Services Location (for GPS location)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Drag & Drop for reordering lists (일정/루트 순서 변경용)
    implementation("org.burnoutcrew.composereorderable:reorderable:0.9.6")

    // Gson for JSON serialization
    implementation("com.google.code.gson:gson:2.10.1")
}
