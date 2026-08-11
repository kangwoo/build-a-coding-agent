plugins {
    application
}

dependencies {
    implementation(project(":agent-core"))
    // 2장 warm-up 클라이언트(agent.warmup.OpenAiChat)가 Jackson을 직접 쓴다.
    // agent-core는 Jackson을 implementation으로만 노출하므로 여기에 다시 선언해야 한다.
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
