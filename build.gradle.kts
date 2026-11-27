plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2")
    }

    implementation("org.mongodb:mongodb-driver-sync:5.2.1") {
        exclude(group = "org.slf4j")
    }
    implementation("org.yaml:snakeyaml:2.3")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

intellijPlatform {
    pluginConfiguration {
        id = "com.maekom.mongolens"
        name = "Maekom Mongo Lens"
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }

    buildSearchableOptions = false
}

tasks {
    test {
        useJUnitPlatform()
    }

    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
}
