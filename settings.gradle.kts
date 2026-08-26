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
        // libadb-android e distribuida pelo JitPack
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "HubTVAgent"
include(":app")
