buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    dependencies {
        classpath("com.google.gms:google-services:4.3.15")
        classpath("com.android.tools.build:gradle:8.0.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.21")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.46.1")
        classpath("androidx.navigation:navigation-safe-args-gradle-plugin:2.5.3")
        classpath("com.google.android.libraries.mapsplatform.secrets-gradle-plugin:secrets-gradle-plugin:2.0.1")
        classpath("com.google.firebase:firebase-crashlytics-gradle:2.9.5")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}