@file:Suppress("UnstableApiUsage")

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Transi"
include(":app")
 
