package com.appdwg.app

import com.appdwg.core.tr

/**
 * Reason of a failure for the user, in their language. The app's own errors already carry a
 * translated message; the system's (empty list, missing file…) come in English and are replaced.
 */
fun errorText(e: Throwable): String = when (e) {
    is java.io.FileNotFoundException -> tr("arquivo não encontrado ou sem permissão de acesso")
    is java.io.IOException -> tr("erro de leitura ou gravação do arquivo")
    is NoSuchElementException, is IndexOutOfBoundsException, is NullPointerException, is ClassCastException,
    is ArithmeticException, is ConcurrentModificationException, is UnsupportedOperationException ->
        tr("os dados não permitiram concluir a operação ({0})", e.javaClass.simpleName)
    is NumberFormatException -> tr("número inválido no arquivo")
    else -> e.message?.takeUnless { it in ENGLISH_DEFAULTS }
        ?: tr("os dados não permitiram concluir a operação ({0})", e.javaClass.simpleName)
}

/** Messages Kotlin gives its own checks (require, check, !!), never meant for users. */
private val ENGLISH_DEFAULTS = setOf("Failed requirement.", "Check failed.", "Required value was null.", "List is empty.", "Collection is empty.")
