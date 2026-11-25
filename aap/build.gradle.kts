// Android Auto Projection (AAP) 手机端协议实现（TLS、控制通道、服务通道）。
//
// 来源：AARE (https://github.com/prototux/AARE) 的 `aap` 模块，GPL-3.0（见 LICENSE-GPL-3.0.txt）。
// 本工程只拷贝了源码 + 预生成的 protobuf Java 类（未启用 protoc 插件，避免构建期依赖 protoc）。
plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.protobuf.javalite)
    api(libs.kotlinx.coroutines.core)
}
