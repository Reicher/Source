pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Self"
include(":app", ":model_pack_1", ":model_pack_2", ":model_pack_3")

plugins {
    id("com.android.application") version "9.3.0" apply false
}
