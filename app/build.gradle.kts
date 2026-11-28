plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.livecontrol.iotmod"
    compileSdk = 35
defaultConfig {
    applicationId = "com.livecontrol.iotmod"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0-spike"

    buildConfigField("String", "DEFAULT_USERNAME", "\"\"")
    buildConfigField("String", "DEFAULT_PASSWORD", "\"\"")
}
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // для теста ставим debug-подпись, чтобы APK устанавливался без своего ключа
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.paho.mqttv3)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
