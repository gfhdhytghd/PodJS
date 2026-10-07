plugins { id("com.android.application") }
val podAppId = providers.gradleProperty("podAppId").orElse("dev.podjs.wear").get()
val podAppName = providers.gradleProperty("podAppName").orElse("PodJS Wear").get()
val podVersionName = providers.gradleProperty("podVersionName").orElse("0.1.0").get()
val podCleartextTraffic = providers.gradleProperty("podCleartextTraffic").orElse("false").get()
val podVersionCode = providers.gradleProperty("podVersionCode").map(String::toInt).orElse(1).get()
val podDistDir = providers.gradleProperty("podDistDir").orElse("../../../dist/wearos-watch").get()
android {
    // Stored bytecode lets AAsset_getBuffer map it without inflate/copy at boot.
    androidResources { noCompress += listOf("pak", "qbc") }
    namespace = "dev.podjs.wear"; compileSdk = 36
    defaultConfig { applicationId = podAppId; minSdk = 30; targetSdk = 36; versionCode = podVersionCode; versionName = podVersionName; manifestPlaceholders["podAppName"] = podAppName; manifestPlaceholders["podCleartextTraffic"] = podCleartextTraffic; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    buildTypes { getByName("release") { isDebuggable = providers.gradleProperty("podReleaseDebuggable").map(String::toBooleanStrict).orElse(false).get() } }
    providers.gradleProperty("podDebugExternalDir").orNull?.let { directory ->
        sourceSets["debug"].manifest.srcFile("$directory/AndroidManifest.xml")
        sourceSets["debug"].java.srcDir("$directory/java")
        if (providers.gradleProperty("podReleaseDebuggable").orElse("false").get() == "true") {
            sourceSets["release"].manifest.srcFile("$directory/AndroidManifest.xml")
            sourceSets["release"].java.srcDir("$directory/java")
        }
    }
    sourceSets["main"].assets.srcDir(podDistDir)
    providers.gradleProperty("podTestSourceDir").orNull?.let { sourceSets["androidTest"].java.srcDir(it) }
}
dependencies {
    implementation(project(":runtime"))
    implementation("androidx.work:work-runtime:2.11.2")
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
