package com.yexclsv.localfiles

/**
 * The C++ module from the template is disabled in app/build.gradle (ninja fails on some
 * Android-storage setups). To re-enable: restore the externalNativeBuild block and ndkVersion,
 * then load "myapplication" here and declare `external fun stringFromJNI(): String`.
 */
object NativeBridge {
    fun stringFromJNI(): String = "Kotlin core 1.0"
}
