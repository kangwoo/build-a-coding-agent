dependencies {
    implementation(rootProject.libs.jackson.databind)
    implementation(rootProject.libs.jackson.jsr310)

    testImplementation(rootProject.libs.junit.jupiter)
    testRuntimeOnly(rootProject.libs.junit.platform.launcher)   // Gradle 9 대비(런처 자동 로딩 폐지)
    testImplementation(rootProject.libs.assertj.core)
    testImplementation(rootProject.libs.wiremock)               // 5장 — 가짜 Chat Completions 응답
}
