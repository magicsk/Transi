import java.util.Properties

plugins {
    id("com.android.application")
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
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.magicsk.transi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = project.properties["version"] as String?

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "IDSBK_API_KEY", "\"${localProps.getProperty("IDSBK_API_KEY", "")}\"")
        manifestPlaceholders["GOOGLE_MAPS_API_KEY"] = localProps.getProperty("GOOGLE_MAPS_API_KEY", "")
    }
    buildFeatures {
        viewBinding = true
        dataBinding = true
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
    implementation("org.jetbrains.kotlin:kotlin-parcelize-runtime:2.2.10")
    add("kotlinCompilerPluginClasspath", "org.jetbrains.kotlin:kotlin-parcelize-compiler:2.2.10")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.browser:browser:1.8.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    implementation("androidx.navigation:navigation-fragment-ktx:2.8.9")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.9")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    implementation("io.coil-kt:coil:2.7.0")

    implementation("com.google.dagger:hilt-android:2.59.2")
    ksp("com.google.dagger:hilt-android-compiler:2.59.2")
    ksp("androidx.hilt:hilt-compiler:1.2.0")
    implementation("androidx.activity:activity-ktx:1.10.1")

    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")

    implementation("io.socket:socket.io-client:2.1.1") {
        exclude(group = "org.json", module = "json")
    }

    implementation("com.github.bumptech.glide:glide:4.16.0")
    ksp("com.github.bumptech.glide:ksp:4.16.0")

    implementation("com.google.android.gms:play-services-maps:19.1.0")
    implementation("com.google.maps.android:android-maps-utils:3.8.2")

    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("com.github.cachapa:ExpandableLayout:2.9.2")

    implementation(platform("com.google.firebase:firebase-bom:33.12.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-crashlytics")

    implementation("io.noties.markwon:core:4.6.2")
    implementation("com.github.android-password-store:sublime-fuzzy:2.2.1")
}
