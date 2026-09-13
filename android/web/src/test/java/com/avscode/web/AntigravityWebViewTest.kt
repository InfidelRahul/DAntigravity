package com.avscode.web

import org.junit.Assert.*
import org.junit.Test

class AntigravityWebViewTest {

    @Test
    fun testResolveLinuxArchitecture() {
        assertEquals("aarch64", AntigravityWebView.resolveLinuxArchitecture("arm64-v8a"))
        assertEquals("armv7l", AntigravityWebView.resolveLinuxArchitecture("armeabi-v7a"))
        assertEquals("x86_64", AntigravityWebView.resolveLinuxArchitecture("x86_64"))
        assertEquals("i686", AntigravityWebView.resolveLinuxArchitecture("x86"))
    }

    @Test
    fun testBuildDesktopUserAgent() {
        val ua = AntigravityWebView.buildDesktopUserAgent(null, "arm64-v8a")
        assertTrue("Must contain Linux aarch64", ua.contains("Linux aarch64"))
        assertTrue("Must contain Chrome token", ua.contains("Chrome/"))
        assertTrue("Must contain Safari token", ua.contains("Safari/"))
    }

    @Test
    fun testIsAuthUrl() {
        assertTrue(AntigravityWebView.isAuthUrl("https://github.com/login/oauth/authorize?client_id=xyz"))
        assertTrue(AntigravityWebView.isAuthUrl("https://accounts.google.com/o/oauth2/v2/auth"))
        assertTrue(AntigravityWebView.isAuthUrl("https://login.microsoftonline.com/common/oauth2/v2.0/authorize"))

        assertFalse(AntigravityWebView.isAuthUrl("https://docs.github.com/en"))
        assertFalse(AntigravityWebView.isAuthUrl("https://antigravity.google/docs"))
    }

    @Test
    fun testIsAuthCallbackUrl() {
        assertTrue(AntigravityWebView.isAuthCallbackUrl("droidantigravity://callback?code=abc"))
        assertTrue(AntigravityWebView.isAuthCallbackUrl("http://127.0.0.1:41235/callback?code=abc", 41235))
        assertFalse(AntigravityWebView.isAuthCallbackUrl("http://127.0.0.1:33000/"))
    }

    @Test
    fun testBuildZoomJavaScript() {
        val js = AntigravityWebView.buildZoomJavaScript(0.75)
        assertTrue("Must set zoom factor variable", js.contains("factor = 0.7500"))
        assertTrue("Must apply zoom to document element", js.contains("docEl.style.zoom = factor"))
        assertTrue("Must set document width", js.contains("docEl.style.width = '100%'"))
        assertTrue("Must fire resize event", js.contains("window.dispatchEvent(new Event('resize'))"))
    }
}
