plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hobby.aa2carlife"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hobby.aa2carlife"
        minSdk = 31
        targetSdk = 37
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
