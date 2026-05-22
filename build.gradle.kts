plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val previewFlags = listOf("--enable-preview")

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(previewFlags + listOf("-source", "21"))
}

tasks.withType<Test>().configureEach {
    jvmArgs(previewFlags)
    useJUnitPlatform {
        excludeTags("slow")
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // ONNX Runtime
    implementation("com.microsoft.onnxruntime:onnxruntime:1.25.0")

    // SQLite
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // JSON
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    // Token counting (OpenAI tiktoken port)
    implementation("com.knuddels:jtokkit:1.1.0")

    // JUnit 5
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // WireMock (programmatic, in-JVM -- no containers needed for HTTP mocking)
    testImplementation("org.wiremock:wiremock:3.10.0")

    // Testcontainers (for optional Ollama slow integration test)
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")

    // Mockito (stub EmbeddingEngine in cache tests)
    testImplementation("org.mockito:mockito-core:5.14.2")
}
