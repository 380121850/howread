rootProject.name = "HowRead"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://developer.huawei.com/repo/") } // HMS Core SDKs (IAP / Ads Kit) + agcp
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://developer.huawei.com/repo/") } // HMS Core SDKs (IAP / Ads Kit) + agcp
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":app")

// :libPro was removed (2026-09): it only held no-op com.google.android.gms.ads
// stand-ins for GMS-free flavors. The ad abstraction (src/admobAds vs
// src/noAds in the app module) replaced that mechanism.
include(":libReflow")

include(":Builder")
// Builder lives at the REPO ROOT (../Builder): it holds the shared C/C++
// MuPDF engine + android JNI glue, also consumed by harmony/ and prebuilt/.
project(":Builder").projectDir = file("../Builder")
include(":libDepFree")
include(":libDepPro")

// KMP experiment seeds (composeApp/shared/iosApp) were deleted 2026-09-02:
// the project does not use Kotlin Multiplatform (see root MULTI_PLATFORM.md).
