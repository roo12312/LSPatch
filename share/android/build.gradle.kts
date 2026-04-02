plugins {
    alias(libs.plugins.agp.lib)
}

android {
    namespace = "com.lspatch.android.share"

    buildFeatures {
        androidResources = false
        buildConfig = false
    }
}

dependencies {
    implementation("vector:daemon-service")
}
