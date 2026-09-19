import nl.littlerobots.vcu.plugin.resolver.VersionSelectors

// buildscript classpath for agcp was removed: the plugin cannot coexist with
// flavors that have no agconnect-services.json (see app/build.gradle note).

plugins {
    id("nl.littlerobots.version-catalog-update") version "1.0.1"
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false

    //alias(libs.plugins.androidxRoom) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    //alias(libs.plugins.spmForKmp) apply false
    //alias(libs.plugins.kotlinJvm) apply false
  
   


}

versionCatalogUpdate {
    versionSelector(VersionSelectors.STABLE)

}

//project.extra.apply {
//    set("targetSdk", 36)
//    set("minSdk", 24)
//    set("compileSdk", 36)
//}
