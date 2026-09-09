plugins { id("com.android.application") }
val podAppId = providers.gradleProperty("podAppId").orElse("dev.podjs.wear").get()
val podAppName = providers.gradleProperty("podAppName").orElse("PodJS Wear").get()
val podVersionName = providers.gradleProperty("podVersionName").orElse("0.1.0").get()
val podVersionCode = providers.gradleProperty("podVersionCode").map(String::toInt).orElse(1).get()
val podDistDir = providers.gradleProperty("podDistDir").orElse("../../../dist/wearos-watch").get()
android {
    androidResources { noCompress += "pak" }
    namespace = "dev.podjs.wear"; compileSdk = 36
    defaultConfig { applicationId = podAppId; minSdk = 30; targetSdk = 36; versionCode = podVersionCode; versionName = podVersionName; manifestPlaceholders["podAppName"] = podAppName; testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    sourceSets["main"].assets.srcDir(podDistDir)
    providers.gradleProperty("podTestSourceDir").orNull?.let { sourceSets["androidTest"].java.srcDir(it) }
}
dependencies {
    implementation(project(":runtime"))
    androidTestImplementation("androidx.test:core:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
