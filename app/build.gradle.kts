plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.aspirelending.jobcards"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.aspirelending.jobcards"
        minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1.0"
    }
    // Fixed signing key so every build has the same SHA-1 (Google Drive sign-in is tied to it). Keep the repository private.
    signingConfigs { create("aspire") { storeFile = file("aspire-debug.keystore"); storePassword = "android"; keyAlias = "aspire"; keyPassword = "android" } }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("aspire") } }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Phase 1 foundation (used from next phases)
    implementation("androidx.room:room-runtime:2.6.1"); implementation("androidx.room:room-ktx:2.6.1"); ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.camera:camera-camera2:1.4.0"); implementation("androidx.camera:camera-lifecycle:1.4.0"); implementation("androidx.camera:camera-view:1.4.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
}
