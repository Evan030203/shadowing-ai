plugins {
    id("com.android.application") version "8.13.0" apply false
    // litertlm-android 0.19.x는 Kotlin 메타데이터 2.4를 쓰므로 Kotlin 2.3 이상이 필요하다.
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0" apply false
}
