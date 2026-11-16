import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvmToolchain(21)
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.vitazone.sync.MainKt"
        nativeDistributions {
            // .msi / .exe собираются только на Windows: gradlew :composeApp:packageMsi
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "VitazoneSheetSync"
            packageVersion = "1.0.0"
            vendor = "Vitazone"
            description = "Выгрузка отчётов 1С в Google Sheets"
            modules("java.net.http")
            windows {
                menuGroup = "Vitazone"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6f2b1c1e-6a57-4c8e-9a52-7d0f3b1f6a11"
            }
        }
    }
}
