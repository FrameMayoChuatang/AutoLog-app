plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// 배포 주소는 로컬 설정에만 두고, 명시적인 빌드 옵션을 우선한다.
val localServerUrl by lazy {
    rootProject.file(".env").takeIf { it.isFile }?.readLines()
    ?.mapNotNull { line ->
        Regex("""^\s*(?:export\s+)?AUTOLOG_SERVER_URL\s*=\s*(.*?)\s*$""")
            .matchEntire(line)?.groupValues?.get(1)
    }?.lastOrNull()?.let { raw ->
        val value = if (raw.firstOrNull() == '\'' || raw.firstOrNull() == '"') {
            val closing = raw.indexOf(raw.first(), startIndex = 1)
            require(closing > 0 && raw.substring(closing + 1).trim().let { it.isEmpty() || it.startsWith("#") }) {
                "AUTOLOG_SERVER_URL의 인용부호와 주석을 확인해 주세요."
            }
            raw.substring(1, closing)
        } else raw.replace(Regex("""\s+#.*$"""), "").trimEnd()
        require(value.isNotBlank()) { "AUTOLOG_SERVER_URL이 비어 있습니다." }
        value
    }
}
val serverUrl = project.findProperty("serverUrl")?.toString()
    ?: System.getenv("AUTOLOG_SERVER_URL")
    ?: localServerUrl
    ?: "http://10.0.2.2:8080"

android {
    namespace = "com.framemayo.autolog"
    compileSdk = 34
    useLibrary("android.car", false)

    defaultConfig {
        applicationId = "com.framemayo.autolog"
        minSdk = 28 // Pleos Playground 배포 요구사항
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        resValue("bool", "use_console_odometer", "false")
        resValue("string", "server_url", "\"$serverUrl\"")
    }

    buildTypes {
        debug {
            resValue("bool", "use_console_odometer",
                (project.findProperty("consoleOdometer")?.toString()?.toBooleanStrict() ?: false).toString())
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.fusedlocation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
