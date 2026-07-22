plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.vpngate.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vpngate.client"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BACKEND_URL", "\"https://zenith.putra.my.id\"")
    }

    signingConfigs {
        create("release") {
            val keystoreFilePath = (System.getenv("RELEASE_KEYSTORE_PATH") ?: project.findProperty("RELEASE_KEYSTORE_PATH") as String?)?.trim()
            val keystorePassword = (System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: project.findProperty("RELEASE_KEYSTORE_PASSWORD") as String?)?.trim()
            val keyAliasName = (System.getenv("RELEASE_KEY_ALIAS") ?: project.findProperty("RELEASE_KEY_ALIAS") as String?)?.trim()
            val keyPasswordVal = (System.getenv("RELEASE_KEY_PASSWORD") ?: project.findProperty("RELEASE_KEY_PASSWORD") as String?)?.trim()

            if (keystoreFilePath != null && keystorePassword != null && keyAliasName != null && keyPasswordVal != null) {
                storeFile = file(keystoreFilePath)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordVal
            } else if (file("signing.keystore").exists()) {
                storeFile = file("signing.keystore")
                storePassword = "vpngatepassword"
                keyAlias = "vpngatekey"
                keyPassword = "vpngatepassword"
            } else {
                storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(libs.coil.compose)

    // Networking & Coroutines
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.coroutines.android)

    // Retrofit + Moshi for backend API
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // OpenVPN Transport Engine
    implementation("io.github.tim06:vpnprotocols-openvpn:3.0.4")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}