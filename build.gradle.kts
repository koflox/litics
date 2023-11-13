import com.vanniktech.maven.publish.MavenPublishPluginExtension
import com.vanniktech.maven.publish.SonatypeHost

val GROUP: String by project
val VERSION_NAME: String by project

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.vanniktech:gradle-maven-publish-plugin:0.18.0")
    }
}

plugins {
    val kotlinVersion = "1.8.0"
    kotlin("jvm") version kotlinVersion apply false
    kotlin("multiplatform") version kotlinVersion apply false
    kotlin("plugin.serialization") version kotlinVersion apply false
}

allprojects {
    repositories {
        mavenCentral()
    }

    apply(plugin = "com.vanniktech.maven.publish")

    extensions.getByType<MavenPublishPluginExtension>().apply {
        sonatypeHost = SonatypeHost.S01
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()
    }

    group = GROUP
    version = VERSION_NAME
}
