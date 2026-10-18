plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
    id("com.chaquo.python")
}

android {
    namespace = "com.cccece.picharbor"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cccece.picharbor"
        minSdk = 29
        targetSdk = 36
        versionCode = 10004
        versionName = "1.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets["main"].java.setSrcDirs(listOf("src/main/java/com"))
    sourceSets["test"].java.setSrcDirs(listOf("src/test/java/com"))
    sourceSets["main"].assets.srcDir(rootProject.file("docs/baseline-assets"))
    signingConfigs {
        create("production") {
            System.getenv("PICHARBOR_KEYSTORE")?.let { storeFile = file(it) }
            storePassword = System.getenv("PICHARBOR_STORE_PASSWORD")
            keyAlias = "picharbor"
            keyPassword = System.getenv("PICHARBOR_KEY_PASSWORD")
        }
    }
    testBuildType = "diagnostic"
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("production")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("diagnostic") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            matchingFallbacks += "release"
        }
    }
}
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas") } }
chaquopy {
    defaultConfig {
        version = "3.13"
        providers.gradleProperty("buildPython").orNull?.let { buildPython(it) }
        pip { install("-r", "requirements.lock") }
    }
}
dependencyLocking { lockAllConfigurations() }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    kapt("androidx.room:room-compiler:2.7.2")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
