plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tusaga.rtkinfra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tusaga.rtkinfra"
        minSdk = 26          // Android 8.0 – GNSS raw measurements stable
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        // TUSAGA-Aktif NTRIP defaults (override via Settings UI)
        buildConfigField("String", "TUSAGA_HOST",  "\"cors.tusaga-aktif.gov.tr\"")
        buildConfigField("int",    "TUSAGA_PORT",  "2101")
        buildConfigField("String", "TUSAGA_MOUNT", "\"TUSK00TUR0\"")
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.viewpager2)

    // Lifecycle + ViewModel
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)

    // Hilt DI
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.android)

    // ARCore + SceneView
    implementation(libs.arcore)
    implementation(libs.sceneview)

    // Maps
    implementation(libs.maps)
    implementation(libs.maps.utils)

    // Network
    implementation(libs.okhttp)
    implementation(libs.retrofit)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // JSON
    implementation(libs.moshi)

    // Logging
    implementation(libs.timber)
}
