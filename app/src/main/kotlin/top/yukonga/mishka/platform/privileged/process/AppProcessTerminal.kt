package top.yukonga.mishka.platform.privileged.process

sealed interface AppProcessTerminal {
    data object Root : AppProcessTerminal
}
