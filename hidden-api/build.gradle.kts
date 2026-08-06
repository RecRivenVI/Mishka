plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "top.yukonga.mishka.hiddenapi"

    compileSdk {
        version = release(ProjectConfig.Android.COMPILE_SDK) {
            minorApiLevel = ProjectConfig.Android.COMPILE_SDK_MINOR
        }
    }
    defaultConfig {
        minSdk = ProjectConfig.Android.MIN_SDK
    }
}
