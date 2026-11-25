plugins { id("com.android.application") }

android {
    namespace = "com.ilan.reminders.face"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ilan.reminders.face"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("../app/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { debug { signingConfig = signingConfigs.getByName("debug") } }
}
