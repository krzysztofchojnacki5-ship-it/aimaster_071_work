plugins {
    id("com.android.application")
}

android {
    namespace = "pl.aimasterpro.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.aiforteca.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2"
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

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    // TLS test fixtures inspect InetAddress internals on JDK 17; this is test JVM only.
    jvmArgs("--add-opens=java.base/java.net=ALL-UNNAMED")
}
