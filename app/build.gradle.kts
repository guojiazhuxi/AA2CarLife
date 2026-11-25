plugins {
    alias(libs.plugins.android.application)
}

val compileSdkOverride = providers.gradleProperty("compileSdkVersion").orNull?.toIntOrNull() ?: 37
val targetSdkOverride = providers.gradleProperty("targetSdkVersion").orNull?.toIntOrNull() ?: 37

android {
    namespace = "com.hobby.aa2carlife"
    compileSdk {
        version = release(compileSdkOverride)
    }

    defaultConfig {
        applicationId = "com.hobby.aa2carlife"
        minSdk = 31
        targetSdk = targetSdkOverride
        versionCode = 1
        versionName = "1.0"

    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":aap"))
    testImplementation(libs.junit)
}
