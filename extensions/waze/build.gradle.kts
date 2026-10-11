extension {
    name = "extensions/waze.mpe"
}

android {
    namespace = "org.ungoogled.waze"

    defaultConfig {
        // Waze 5.24 itself requires Android 12L (API 32).
        minSdk = 32
    }
}

// Plain Java, like the Maps extension: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}
