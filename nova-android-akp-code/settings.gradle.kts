pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    // Explicitly pinned to 1.0.0+ — a real, current, common Gradle 9 issue:
    // versions below 1.0.0 reference a constant (JvmVendorSpec.IBM_SEMERU)
    // that Gradle removed in 9.0.0, crashing with "does not have member
    // field IBM_SEMERU" the moment Gradle tries to resolve a JVM toolchain.
    // This project never explicitly declared this plugin before — it gets
    // pulled in automatically by Android Studio's own tooling (JVM toolchain
    // auto-provisioning), and without an explicit version pin here, that
    // auto-included version can be an old, incompatible one depending on
    // which Android Studio version opens this project. Pinning it here
    // takes that out of Android Studio's hands entirely.
    //
    // Real mistake caught immediately after first writing this: pluginManagement
    // MUST be the first block in a Gradle settings file when present — a
    // top-level plugins {} block has to come after it, not before.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Nova"
include(":app")
