package top.yukonga.mishka.platform.privileged

class PrivilegedOperationProvider {
    fun openSession(authorizer: Authorizer): DirectPrivilegedSession? {
        if (authorizer == Authorizer.None) return null
        return openDirectPrivileged(authorizer)
    }

    fun warmUp(authorizer: Authorizer): Boolean {
        return runDirectPrivilegedOrNull(authorizer) { true } == true
    }
}
