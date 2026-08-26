plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.manu.myapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.manu.myapp"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

    //aggiunte
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}