import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

// Secrets replacement — read from local.properties
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) load(file.inputStream())
}

android {
    compileSdk = 37

    defaultConfig {
        applicationId = "eu.magicsk.transi"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = providers.gradleProperty("version").orNull

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        listOf("IDSBK_API_KEY", "THUNDERFOREST_API_KEY", "R_API_KEY").forEach { key ->
            buildConfigField("String", key, "\"${localProps.getProperty(key, "")}\"")
        }
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = localProps.getProperty("GOOGLE_MAPS_API_KEY", "")
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    namespace = "eu.magicsk.transi"
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.browser:browser:1.10.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    implementation("androidx.navigation:navigation-fragment-ktx:2.10.2")
    implementation("androidx.navigation:navigation-ui-ktx:2.10.2")

    implementation("com.squareup.retrofit2:retrofit:3.0.0")
    implementation("com.squareup.retrofit2:converter-gson:3.0.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-android-compiler:2.60.1")
    implementation("androidx.activity:activity-ktx:1.13.0")

    implementation("com.squareup.retrofit2:converter-scalars:3.0.0")

    implementation("io.socket:socket.io-client:2.1.2") {
        exclude(group = "org.json", module = "json")
    }

    implementation("com.github.bumptech.glide:glide:5.0.9")

    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("com.google.maps.android:android-maps-utils:6.0.0")

    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("com.github.cachapa:ExpandableLayout:2.9.2")

    implementation(platform("com.google.firebase:firebase-bom:35.0.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")

    implementation("io.noties.markwon:core:4.6.2")
    implementation("com.github.android-password-store:sublime-fuzzy:2.3.4")
}
