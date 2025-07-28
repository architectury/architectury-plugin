import org.gradle.api.JavaVersion
import org.gradle.kotlin.dsl.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.25"
    java
    idea
    eclipse
    `maven-publish`
    `kotlin-dsl`
}

group = "me.shedaniel"

val base_version: String by project
val isSnapshot = System.getenv("PR_NUM") != null
val runNumber = System.getenv("GITHUB_RUN_NUMBER") ?: "9999"

version = if (isSnapshot) {
    "$base_version-PR.${System.getenv("PR_NUM")}.$runNumber"
} else {
    "$base_version.$runNumber"
}

val pluginId = "architectury-plugin"

logger.lifecycle(":building architectury plugin v$version")

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

repositories {
    maven("https://maven.fabricmc.net/")
    maven("https://maven.minecraftforge.net/")
    maven("https://maven.shedaniel.me/")
    gradlePluginPortal()
    mavenLocal()
}

apply(plugin = "java-gradle-plugin")

sourceSets {
    val main by getting
    listOf(
        "loom06",
        "loom09",
        "loom010Legacy",
        "loom010",
        "loom011",
        "loom11"
    ).forEach { name ->
        create(name) {
            java.srcDir("src/$name/java")
            compileClasspath += main.compileClasspath + main.output
            runtimeClasspath += main.runtimeClasspath + main.output
        }
    }
}

val transformer_version: String by project
val loom_version_06: String by project
val loom_version_09: String by project
val loom_version_010Legacy: String by project
val loom_version_010: String by project
val loom_version_011: String by project
val loom_version_11: String by project

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.3.72")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.3.72")
    implementation("gradle.plugin.org.jetbrains.gradle.plugin.idea-ext:gradle-idea-ext:0.10")
    implementation("dev.architectury:architectury-transformer:$transformer_version")
    add("loom06CompileOnly", "me.shedaniel:forgified-fabric-loom:$loom_version_06")
    add("loom09CompileOnly", "dev.architectury:architectury-loom:$loom_version_09")
    add("loom010LegacyCompileOnly", "dev.architectury:architectury-loom:$loom_version_010Legacy")
    add("loom010CompileOnly", "dev.architectury:architectury-loom:$loom_version_010")
    add("loom011CompileOnly", "dev.architectury:architectury-loom:$loom_version_011")
    add("loom011CompileOnly", "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.5.10")
    add("loom011CompileOnly", "org.jetbrains.kotlin:kotlin-reflect:1.5.10")
    add("loom11CompileOnly", "dev.architectury:architectury-loom:$loom_version_11")
    implementation("dev.architectury:tiny-remapper:1.1.0")
    implementation("com.google.code.gson:gson:2.8.5")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += "-Xjvm-default=all"
    }
}

tasks.jar {
    manifest {
        attributes(mapOf("Implementation-Version" to project.version))
    }

    val outputs = listOf(
        "loom06", "loom09", "loom010Legacy", "loom010", "loom011", "loom11"
    ).map { sourceSets[it].output }

    from(outputs)
}

gradlePlugin {
    plugins {
        create("architect") {
            id = pluginId
            implementationClass = "dev.architectury.plugin.ArchitecturyPlugin"
        }
    }
}

val sourcesJar by tasks.registering(Jar::class) {
    archiveClassifier.set("sources")
    from(sourceSets["main"].allSource)
    dependsOn("classes")
}

publishing {
    publications {
        create<MavenPublication>("plugin") {
            groupId = pluginId
            artifactId = "$pluginId.gradle.plugin"
            from(components["java"])
            artifact(sourcesJar.get())
        }
        create<MavenPublication>("pluginSnapshot") {
            groupId = pluginId
            artifactId = "$pluginId.gradle.plugin"
            version = "${project.extra["base_version"]}-SNAPSHOT"
            from(components["java"])
            artifact(sourcesJar.get())
        }
    }

    repositories {
        System.getenv("MAVEN_PASS")?.let {
            maven {
                url = uri("https://deploy.shedaniel.me/")
                credentials {
                    username = "shedaniel"
                    password = it
                }
            }
        }
    }
}