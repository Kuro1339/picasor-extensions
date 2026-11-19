plugins {
    id("com.android.application")
}

android {
    namespace = "eu.kanade.tachiyomi.extension.es.picazor"
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.es.picazor"
        minSdk = AndroidConfig.minSdk
        targetSdk = AndroidConfig.targetSdk
        versionCode = 1
        versionName = "1.4.1"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

dependencies {
    implementation(project(":lib"))
}
