package com.sednium.kryptonide.ui.terminal

import org.junit.Assert.*
import org.junit.Test

class KryptonShellEngineTest {

    @Test
    fun testCanHandleBuiltinCommands() {
        assertTrue("npm should be handled", KryptonShellEngine.canHandle("npm install react"))
        assertTrue("npx should be handled", KryptonShellEngine.canHandle("npx vite"))
        assertTrue("py should be handled", KryptonShellEngine.canHandle("py main.py"))
        assertTrue("python should be handled", KryptonShellEngine.canHandle("python script.py"))
        assertTrue("pip should be handled", KryptonShellEngine.canHandle("pip install requests"))
        assertTrue("node should be handled", KryptonShellEngine.canHandle("node server.js"))
        assertTrue("gh should be handled", KryptonShellEngine.canHandle("gh repo list"))
        assertTrue("git should be handled", KryptonShellEngine.canHandle("git status"))
        assertTrue("help should be handled", KryptonShellEngine.canHandle("help"))
    }

    @Test
    fun testBuiltinCommandsListCompleteness() {
        val builtins = KryptonShellEngine.BUILTIN_COMMANDS
        assertTrue(builtins.contains("npm"))
        assertTrue(builtins.contains("npx"))
        assertTrue(builtins.contains("py"))
        assertTrue(builtins.contains("python"))
        assertTrue(builtins.contains("pip"))
        assertTrue(builtins.contains("gh"))
        assertTrue(builtins.contains("node"))
    }
}
