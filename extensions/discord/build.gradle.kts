plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.android.lint)
}

kotlin {
    androidLibrary {
        namespace = "me.knighthat.discord"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()

        // Explicitly declare both build types so that AGP attaches
        // BuildTypeAttr to each published variant.  Without this, the
        // android.kotlin.multiplatform.library plugin emits a single
        // androidRuntimeElements configuration with no BuildTypeAttr,
        // causing Gradle to see multiple matching candidates and fail
        // with variant-ambiguity when any consumer (e.g. the licence-
        // report task) asks for a specific build type like 'release'.
        buildTypes {
            release {}
            debug {}
        }
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
