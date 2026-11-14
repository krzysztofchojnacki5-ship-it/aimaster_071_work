plugins {
    id("com.android.application")
}

val releaseSigningEnvironment = listOf(
    "AIFORTECA_KEYSTORE_PATH",
    "AIFORTECA_KEYSTORE_PASSWORD",
    "AIFORTECA_KEY_ALIAS",
    "AIFORTECA_KEY_PASSWORD"
).associateWith { System.getenv(it).orEmpty() }
val hasAnyReleaseSigningValue = releaseSigningEnvironment.values.any { it.isNotBlank() }
val hasCompleteReleaseSigningEnvironment = releaseSigningEnvironment.values.all { it.isNotBlank() }

if (hasAnyReleaseSigningValue && !hasCompleteReleaseSigningEnvironment) {
    throw GradleException("Release signing is partially configured. Provide all AIFORTECA signing environment variables or none.")
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

    signingConfigs {
        if (hasCompleteReleaseSigningEnvironment) {
            create("release") {
                storeFile = file(releaseSigningEnvironment.getValue("AIFORTECA_KEYSTORE_PATH"))
                storePassword = releaseSigningEnvironment.getValue("AIFORTECA_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningEnvironment.getValue("AIFORTECA_KEY_ALIAS")
                keyPassword = releaseSigningEnvironment.getValue("AIFORTECA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = false
            if (hasCompleteReleaseSigningEnvironment) {
                signingConfig = signingConfigs.getByName("release")
            }
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
