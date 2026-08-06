plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.rosan.app_process"

    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }
    defaultConfig {
        minSdk = ProjectConfig.Android.MIN_SDK
    }
    buildFeatures {
        aidl = true
    }
}

dependencies {
    compileOnly(projects.hiddenApi)
    compileOnly(libs.androidx.annotation)

    implementation(libs.commons.cli)
    implementation(libs.hiddenapibypass)
}
