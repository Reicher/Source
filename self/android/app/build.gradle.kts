plugins {
    id("com.android.application")
}

android {
    namespace = "com.source.self"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.source.self"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    assetPacks += listOf(":model_pack_1", ":model_pack_2", ":model_pack_3")

    androidResources {
        noCompress += "part"
    }
}
