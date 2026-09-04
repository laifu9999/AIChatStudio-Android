plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lele.mobipaint"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lele.mobipaint"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
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
    }
    // 关键：主干仓库上共存着「乐乐写小说」(com/lele/novelmaster) 的源码，
    // 墨笔构建时必须排除（java 与 kotlin 两个源集都要排）
    sourceSets.getByName("main") {
        java.exclude("com/lele/novelmaster/**")
    }
}

// Kotlin 编译任务(compileDebugKotlin)用的是 KGP 的 kotlin 源集，必须单独排除
kotlin {
    sourceSets.getByName("main") {
        kotlin.exclude("com/lele/novelmaster/**")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
