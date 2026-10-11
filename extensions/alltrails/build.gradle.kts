extension {
    name = "extensions/alltrails.mpe"
}

android {
    namespace = "org.ungoogled.alltrails"

    defaultConfig {
        // AllTrails 26.3 itself requires Android 9 (API 28).
        minSdk = 28
    }
}

// Plain Java, like the Maps extension: keep the Kotlin stdlib out of the dex.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
    // The Material Components version AllTrails 26.3 bundles (META-INF/com.google.android.material_material.version).
    // Compile-only: the extension calls AllTrails' own copy, whose Slider and SwitchMaterial API R8 left intact.
    compileOnly("com.google.android.material:material:1.7.0")
}
