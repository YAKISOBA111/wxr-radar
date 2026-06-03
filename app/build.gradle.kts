plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace         = "com.wxr.radar"
    compileSdk        = 34

    defaultConfig {
        applicationId = "com.wxr.radar"
        minSdk        = 26
        targetSdk     = 34
        versionCode   = 1
        versionName   = "1.0"
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
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.livedata)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.constraintlayout)
    implementation(libs.play.location)

    // Android Auto / Automotive
    implementation(libs.car.app)
    // Automotive OS (実機ビルド時のみ有効)
    // implementation(libs.car.app.automotive)
}
