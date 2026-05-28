plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
}

kotlin {
    // Note: android.kotlin.multiplatform.library does not expose a buildTypes {}
    // DSL inside androidLibrary {} at AGP 8.13.2.  Build types (debug/release)
    // are created automatically by the plugin; variant publishing of BuildTypeAttr
    // is a known upstream limitation tracked separately.
    androidLibrary {
        namespace = "me.knighthat.discord"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation( libs.koin.core )
            implementation( libs.kermit )
            implementation( libs.bundles.ktor )
        }
        commonTest.dependencies {
            implementation( libs.kotlin.test )
        }
        androidMain.dependencies {
            implementation( projects.kizzy )
        }
    }

    compilerOptions {
        freeCompilerArgs.add( "-Xexpect-actual-classes" )
    }
}
// ci-trigger
