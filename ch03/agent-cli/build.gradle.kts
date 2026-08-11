plugins {
    application
}

dependencies {
    // 2장의 스파이크 클라이언트(agent.warmup.OpenAiChat)와 Jackson을 쓴다.
    implementation(rootProject.libs.jackson.databind)
    implementation(rootProject.libs.jline)
    implementation(rootProject.libs.picocli)

    testImplementation(rootProject.libs.junit.jupiter)
    testRuntimeOnly(rootProject.libs.junit.platform.launcher)   // Gradle 9 대비(런처 자동 로딩 폐지)
    testImplementation(rootProject.libs.assertj.core)
}

application {
    mainClass.set("agent.cli.Main")
}
