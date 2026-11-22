plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.watchgallery.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.watchgallery.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 63
        versionName = "0.63"
        vectorDrawables { useSupportLibrary = true }

        // 检查更新用的仓库地址：Gitee 优先（国内直连快），GitHub 作为备用源。
        // 具体仓库名由 flavor 决定（见 productFlavors）：
        //   official → weargallery_release（只放发布包，不含源码）
        //   self     → WatchGallery（源码仓库，也发 release）
        buildConfigField("String", "GITEE_OWNER", "\"vveyin\"")
        buildConfigField("String", "GITHUB_OWNER", "\"vveyin\"")
    }

    /**
     * 两个渠道，构建产物分别给「正式发布」和「自己调试」用。
     *
     * 差别只有一处：检测更新读哪个仓库。这样正式包的用户永远只看到正式发布包，
     * 调试包则跟着源码仓库走（想装哪个就装哪个，两者同包名同签名，后装的覆盖先装的）。
     */
    flavorDimensions += "channel"
    productFlavors {
        create("official") {
            dimension = "channel"
            buildConfigField("String", "GITEE_REPO", "\"weargallery_release\"")
            buildConfigField("String", "GITHUB_REPO", "\"weargallery_release\"")
            buildConfigField("String", "CHANNEL", "\"official\"")
        }
        create("self") {
            dimension = "channel"
            buildConfigField("String", "GITEE_REPO", "\"WatchGallery\"")
            buildConfigField("String", "GITHUB_REPO", "\"WatchGallery\"")
            buildConfigField("String", "CHANNEL", "\"self\"")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../watchgallery.jks")
            storePassword = "watchgallery2024"
            keyAlias = "watchgallery"
            keyPassword = "watchgallery2024"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("io.coil-kt:coil-compose:2.6.0")
    // 动图（GIF）解码：Coil 默认只解第一帧
    implementation("io.coil-kt:coil-gif:2.6.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.documentfile:documentfile:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
