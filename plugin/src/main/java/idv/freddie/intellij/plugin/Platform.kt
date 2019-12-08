package idv.freddie.intellij.plugin

private val platformName = System.getProperty("os.name").toLowerCase()

fun isWindows(): Boolean {
    return platformName.contains("win")
}

fun isMac(): Boolean {
    return platformName.contains("mac")
}
