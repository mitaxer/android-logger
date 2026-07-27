plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android") version "2.1.20"
}

android {
    namespace = "com.mitaxer.logger.sample"
    ndkVersion = "27.0.12077973"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mitaxer.logger.sample"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation(project(":library"))
    implementation("androidx.appcompat:appcompat:1.6.1")
}
