plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android { namespace = "de.pascal.casambibridge"; compileSdk = 35
    defaultConfig { applicationId = "de.pascal.casambibridge"; minSdk = 27; targetSdk = 35; versionCode = 75; versionName = "0.3.5" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.appcompat:appcompat:1.7.0"); implementation("com.google.android.material:material:1.12.0"); implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5"); implementation("eu.agno3.jcifs:jcifs-ng:2.1.10") }
