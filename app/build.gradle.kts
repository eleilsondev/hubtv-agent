plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chave de assinatura FIXA, entregue pelo secret HUBTV_KEYSTORE_BASE64.
//
// Sem ela o Gradle gera uma debug.keystore nova a cada build, e cada APK sai
// assinado com uma chave diferente. Consequencias que ja custaram caro:
//   - o ANDROID_ID deriva da chave de assinatura, entao mudava a cada build:
//     o painel cadastrava um aparelho novo e o codigo de ativacao trocava;
//   - `pm install -r` sobre a versao anterior falha por assinatura
//     incompativel, entao o auto-update nunca poderia funcionar.
// O arquivo NUNCA e commitado: o repositorio e publico.
val chaveFixa = file("hubtv-assinatura.p12")

android {
    namespace = "com.hubtv.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hubtv.agent"
        minSdk = 21
        targetSdk = 34
        // A build da nuvem injeta o numero da execucao; localmente fica "dev".
        // E assim que o painel sabe qual build cada aparelho esta rodando.
        val numeroBuild = (System.getenv("BUILD_NUMBER") ?: "").toIntOrNull()
        versionCode = numeroBuild ?: 1
        versionName = if (numeroBuild != null) "b$numeroBuild" else "dev"
    }

    signingConfigs {
        if (chaveFixa.exists()) {
            create("fixa") {
                storeFile = chaveFixa
                storePassword = "hubtv"
                keyAlias = "hubtv"
                keyPassword = "hubtv"
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (chaveFixa.exists()) signingConfig = signingConfigs.getByName("fixa")
        }
        release {
            isMinifyEnabled = false
            if (chaveFixa.exists()) signingConfig = signingConfigs.getByName("fixa")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // a libadb usa recursos de Java 8+
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }

    packaging {
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*"
        )
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- o coracao do agente ----
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    // O certificado X509 e montado a mao (DER puro) dentro do AdbManager,
    // sem nenhuma biblioteca: o BouncyCastle some classes de ASN.1 no
    // runtime e o sun-security-android e descartado pelo AGP. Zero deps de
    // cripto aqui e a unica forma que compila e roda de forma confiavel.
}
