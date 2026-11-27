plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Banco (27 Nov 2026): the audit bench. A home screen that hosts every Cerniera widget for real,
// as a launcher does, so the audit photographs what Mechack's launcher shows, not a painter's preview.
android {
    namespace = "com.cerniera.banco"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cerniera.banco"
        minSdk = 31
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
