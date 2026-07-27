plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

android {
    namespace = "com.mitaxer.logger"
    ndkVersion = "27.0.12077973"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    publishing {
        singleVariant("release")
    }
}

dependencies {
    // kotlin-stdlib 由 Kotlin Android 插件自动引入，不写死版本
    // 避免与宿主项目的 Kotlin 版本冲突
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.mitaxer"
                artifactId = "android-logger"
                version = System.getenv("VERSION") ?: "1.0"
            }
        }
    }
}
