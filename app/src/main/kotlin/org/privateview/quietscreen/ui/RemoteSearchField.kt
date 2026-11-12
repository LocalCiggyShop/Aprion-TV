package org.privateview.quietscreen.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.awaitCancellation
import org.privateview.quietscreen.core.SearchCaret
import org.privateview.quietscreen.core.SearchTypingAnchor

/** Native editor state preserves the selected insertion point when the IME opens. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun RemoteSearchField(query: String, initialCursor: Int, onQueryChange: (String) -> Unit,
    onCursorChange: (Int) -> Unit, onRetainEditor: (String, Int) -> Unit, onSearch: () -> Unit, leaveEditor: () -> Unit,
    modifier: Modifier = Modifier) {
    // Queries stay in memory; the convenience rememberTextFieldState also saves instance state.
    val editor = remember { TextFieldState(query, TextRange(SearchCaret.clamp(query, initialCursor))) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val typingAnchor = remember { SearchTypingAnchor() }
    val boundInput = remember { InputTransformation.maxLength(200).then(InputTransformation {
        typingAnchor.correction(toString(), selection.start, selection.end)?.let { selection = TextRange(it) }
    }) }
    val keyboard = LocalSoftwareKeyboardController.current
    val imeVisible = WindowInsets.isImeVisible
    var focused by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var sawKeyboard by remember { mutableStateOf(false) }
    var disposed by remember { mutableStateOf(false) }
    var openArmed by remember { mutableStateOf(false) }
    var backArmed by remember { mutableStateOf(false) }
    val inputInterceptor = remember(typing) {
        val allowInput = typing
        PlatformTextInputInterceptor { request, next ->
            if (allowInput) next.startInputMethod(request) else awaitCancellation()
        }
    }
    val currentQuery by rememberUpdatedState(query)
    val updateQuery by rememberUpdatedState(onQueryChange)
    val updateCursor by rememberUpdatedState(onCursorChange)
    val retainEditor by rememberUpdatedState(onRetainEditor)

    fun commitEditor() {
        if (!disposed) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                updateQuery(editor.text.toString())
                updateCursor(editor.selection.end)
            } else retainEditor(editor.text.toString(), editor.selection.end)
        }
    }
    fun leave() {
        typingAnchor.clear()
        commitEditor(); typing = false; openArmed = false; keyboard?.hide(); leaveEditor()
    }
    fun closeKeyboard() {
        typingAnchor.clear()
        commitEditor(); typing = false; sawKeyboard = false; openArmed = false; keyboard?.hide()
    }
    fun handleCaretKey(event: KeyEvent): Boolean {
        if (typing) return false
        if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
            if (event.type == KeyEventType.KeyDown) {
                val next = SearchCaret.move(editor.text.toString(), editor.selection.end,
                    if (event.key == Key.DirectionLeft) -1 else 1)
                editor.edit { selection = TextRange(next) }
            }
            return true
        }
        if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
            if (event.type == KeyEventType.KeyDown) openArmed = true
            else if (event.type == KeyEventType.KeyUp && openArmed) {
                typingAnchor.arm(editor.text.toString(), editor.selection.end)
                openArmed = false; typing = true
            }
            return true
        }
        return false
    }
    LaunchedEffect(query) {
        if (editor.text.toString() != query) { typingAnchor.clear(); editor.edit {
            replace(0, length, query)
            selection = TextRange(query.length)
        } }
    }
    LaunchedEffect(editor, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { editor.text.toString() }.distinctUntilChanged().collect { text ->
                if (text != currentQuery) updateQuery(text)
            }
        }
    }
    LaunchedEffect(editor, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            snapshotFlow { editor.selection.end }.distinctUntilChanged().collect { updateCursor(it) }
        }
    }
    LaunchedEffect(typing, focused, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (focused && typing) { withFrameNanos { }; keyboard?.show() }
        }
    }
    LaunchedEffect(imeVisible) {
        if (imeVisible && focused && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { sawKeyboard = true; typing = true }
        else if (!imeVisible && sawKeyboard) { sawKeyboard = false; typing = false; openArmed = false }
    }
    DisposableEffect(lifecycle) {
        disposed = false
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) {
            typing = false; openArmed = false; backArmed = false; sawKeyboard = false
            typingAnchor.clear()
            retainEditor(editor.text.toString(), editor.selection.end)
            keyboard?.hide()
        } }
        lifecycle.addObserver(observer)
        onDispose {
            disposed = true; lifecycle.removeObserver(observer)
            typingAnchor.clear()
            retainEditor(editor.text.toString(), editor.selection.end)
            keyboard?.hide()
        }
    }
    BackHandler(enabled = focused) {
        if (typing || imeVisible) closeKeyboard()
        else leave()
    }
    // Hiding IME alone leaves its connection alive; cancel it during remote-only caret editing.
    InterceptPlatformTextInput(inputInterceptor) {
      Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BasicTextField(state = editor,
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { state ->
                    focused = state.isFocused
                    openArmed = false; backArmed = false
                    if (state.isFocused) typing = editor.text.isEmpty()
                    else { typingAnchor.clear(); commitEditor(); typing = false; sawKeyboard = false; keyboard?.hide() }
                }
                .onPreInterceptKeyBeforeSoftKeyboard { event ->
                    if (typing && event.type == KeyEventType.KeyDown) typingAnchor.clear()
                    // Older IMEs consume Back without reporting visibility through window insets.
                    if (event.key == Key.Back && (typing || imeVisible || backArmed)) {
                        if (event.type == KeyEventType.KeyDown) backArmed = true
                        else if (event.type == KeyEventType.KeyUp && backArmed) {
                            backArmed = false; closeKeyboard()
                        }
                        true
                    } else handleCaretKey(event)
                }
                .onPreviewKeyEvent { event ->
                    when {
                        handleCaretKey(event) -> true
                        event.key == Key.DirectionDown -> {
                            if (event.type == KeyEventType.KeyDown) leave()
                            true
                        }
                        else -> false
                    }
                }
                .background(RaisedPanel, RoundedCornerShape(10.dp))
                .border(if (focused) 2.dp else 1.dp, if (focused) Accent else RaisedPanel, RoundedCornerShape(10.dp))
                .padding(14.dp),
            inputTransformation = boundInput,
            lineLimits = TextFieldLineLimits.SingleLine,
            textStyle = TextStyle(color = Cloud, fontSize = 19.sp), cursorBrush = SolidColor(Accent),
            // Starting typing must also establish the platform input session, not only show IME.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false,
                showKeyboardOnFocus = typing),
            onKeyboardAction = { _ -> leave(); onSearch() },
            decorator = { inner -> Box {
                if (editor.text.isEmpty()) Text("Title, channel or video ID", color = Muted, fontSize = 19.sp)
                inner()
            } })
        if (focused) Text(if (typing) "Back Close keyboard" else "← → Move cursor · OK Type · ↓ Search · Back Return",
            color = Muted, fontSize = 12.sp)
      }
    }
}
