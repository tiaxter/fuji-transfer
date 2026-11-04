plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    create("selftest") {
        java.srcDir("src/test/java")
        compileClasspath += sourceSets["main"].output
        runtimeClasspath += sourceSets["main"].output
    }
}

// ./gradlew :core:selfTest  -> runs the protocol tests against an in-process fake Fuji camera
tasks.register<JavaExec>("selfTest") {
    group = "verification"
    classpath = sourceSets["selftest"].runtimeClasspath
    mainClass.set("com.fujixfer.core.SelfTest")
}
