dependencies {
    implementation(rootProject.libs.jackson.databind)
    implementation(rootProject.libs.jackson.jsr310)
    implementation(rootProject.libs.jackson.jdk8)               // 9장 — Optional 역직렬화
    implementation(rootProject.libs.java.diff.utils)            // 10장 — Edit diff(patch)

    testImplementation(rootProject.libs.junit.jupiter)
    testRuntimeOnly(rootProject.libs.junit.platform.launcher)   // Gradle 9 대비(런처 자동 로딩 폐지)
    testImplementation(rootProject.libs.assertj.core)
    testImplementation(rootProject.libs.wiremock)               // 5장 — 가짜 Chat Completions 응답
}
