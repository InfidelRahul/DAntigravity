package com.droidantigravity.terminal

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.Terminal

private data class TerminalKey(val label: String, val sequence: String)

/**
 * Touch-first, macOS-inspired terminal surface. The terminal emulator itself
 * remains a real VT/xterm implementation; this composable only supplies UI chrome.
 */
@Composable
fun TerminalScreen(
    session: PtyTerminalSession,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val keys = remember {
        listOf(
            TerminalKey("Esc", PtyTerminalSession.KEY_ESC),
            TerminalKey("Tab", PtyTerminalSession.KEY_TAB),
            TerminalKey("↑", PtyTerminalSession.KEY_UP),
            TerminalKey("↓", PtyTerminalSession.KEY_DOWN),
            TerminalKey("←", PtyTerminalSession.KEY_LEFT),
            TerminalKey("→", PtyTerminalSession.KEY_RIGHT),
            TerminalKey("Ctrl-C", PtyTerminalSession.KEY_CTRL_C),
            TerminalKey("Ctrl-D", PtyTerminalSession.KEY_CTRL_D),
            TerminalKey("Ctrl-Z", PtyTerminalSession.KEY_CTRL_Z)
        )
    }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(session) {
        session.start()
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF0B0B0D)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // macOS-inspired compact title bar.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF18181B))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(10.dp).background(Color(0xFFFF5F57), CircleShape))
                    Box(Modifier.size(10.dp).background(Color(0xFFFFBD2E), CircleShape))
                    Box(Modifier.size(10.dp).background(Color(0xFF28C840), CircleShape))
                }
                Text(
                    text = "Linux Terminal",
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                    color = Color(0xFFE7E7EA),
                    fontSize = 13.sp
                )
                IconButton(onClick = onClose) {
                    Text("×", color = Color(0xFFB7B7BD), fontSize = 22.sp)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF09090B))
            ) {
                Terminal(
                    terminalEmulator = session.emulator,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color(0xFF09090B),
                    foregroundColor = Color(0xFFE8E8EA),
                    keyboardEnabled = true,
                    showSoftKeyboard = true,
                    initialFontSize = 13.sp,
                    minFontSize = 9.sp,
                    maxFontSize = 24.sp,
                    onTerminalTap = { }
                )
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF151517))
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    TerminalAction("Paste") {
                        pasteFromClipboard(context, session)
                    }
                }
                items(keys) { key ->
                    TerminalAction(key.label) { session.sendControl(key.sequence) }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111113))
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    "Default Linux shell • PTY",
                    color = Color(0xFF85858C),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(vertical = 7.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalAction(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(width = 72.dp, height = 38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF242428),
            contentColor = Color(0xFFE8E8EA)
        ),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
    ) {
        Text(label, fontSize = 11.sp)
    }
}

private fun pasteFromClipboard(context: Context, session: PtyTerminalSession) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
    if (text.isNotEmpty()) session.paste(text)
}
