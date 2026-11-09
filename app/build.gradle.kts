plugins {
    id("com.android.application")
}

android {
    namespace = "pl.aimasterpro.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.aimasterpro.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 80
        versionName = "0.6.54"
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
}
