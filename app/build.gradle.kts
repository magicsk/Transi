plugins {
    id("com.android.application")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("dagger.hilt.android.plugin")
    id("kotlin-android")
    id("kotlin-kapt")
    id("kotlin-parcelize")
    id("androidx.navigation.safeargs.kotlin")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
    id("org.jetbrains.kotlin.android")
}
@Suppress("UnstableApiUsage")
android {
    compileSdk = 33

    defaultConfig {
        applicationId = "eu.magicsk.transi"
        minSdk = 26
        targetSdk = 33
        versionCode = 1
        versionName = project.properties["version"] as String?

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        viewBinding = true
        dataBinding = true
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
    kotlinOptions {
        jvmTarget = "17"
    }
    namespace = "eu.magicsk.transi"
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.legacy:legacy-support-v4:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.core:core-ktx:1.10.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")

    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.10.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.10.0")

    implementation("io.coil-kt:coil:2.1.0")
    implementation("com.google.accompanist:accompanist-coil:0.15.0")

    implementation("com.google.dagger:hilt-android:2.46.1")
    kapt("com.google.dagger:hilt-android-compiler:2.46.1")
    kapt("androidx.hilt:hilt-compiler:1.0.0")
    implementation("androidx.activity:activity-ktx:1.7.2")

    implementation("com.squareup.retrofit2:converter-scalars:2.9.0")

    implementation("io.socket:socket.io-client:1.0.1") {
        exclude(group = "org.json", module = "json")
    }

    implementation("com.github.bumptech.glide:glide:4.13.2")
    kapt("com.github.bumptech.glide:compiler:4.13.2")

    implementation("com.google.android.gms:play-services-maps:18.1.0")
    implementation("com.google.maps.android:android-maps-utils:2.4.0")

    implementation("com.google.android.flexbox:flexbox:3.0.0")
    implementation("com.github.cachapa:ExpandableLayout:2.9.2")

    implementation(platform("com.google.firebase:firebase-bom:32.1.1"))
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")

    implementation("io.noties.markwon:core:4.6.2")
    implementation("com.github.android-password-store:sublime-fuzzy:2.2.1")
}
