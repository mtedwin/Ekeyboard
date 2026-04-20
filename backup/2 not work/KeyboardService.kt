package com.mtedwin.ekeyboard

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.text.append
import kotlin.text.clear
import kotlin.toString

class KeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {
    private lateinit var keyboardView: NoDoubleTapKeyboardView
    private lateinit var keyboard: Keyboard
    private var isT13: Boolean = true
    private lateinit var predictionBar: LinearLayout
    private lateinit var prediction1: TextView
    private lateinit var prediction2: TextView
    private lateinit var prediction3: TextView
    private var dictionary: Set<String> = emptySet()
    private val prefixMap = mutableMapOf<String, List<String>>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var predictionJob: Job? = null
    private var isChinese: Boolean = false
    private var chineseDictionary: Map<String, String> = emptyMap()
    private var cangjieMap: Map<String, String> = emptyMap()
    private var chineseInput = StringBuilder()



    private var cangjieInput = StringBuilder()

    override fun onCreateInputView(): View {
        val rootView = layoutInflater.inflate(R.layout.keyboard_view, null) as LinearLayout
        keyboardView = rootView.findViewById(R.id.keyboard_view)
        keyboard = Keyboard(this, if (isT13) R.xml.t13 else R.xml.qwerty)
        keyboardView.keyboard = keyboard
        keyboardView.setOnKeyboardActionListener(this)
        keyboardView.isPreviewEnabled = false
        keyboardView.bypassTouchHandling = isT13

        predictionBar = rootView.findViewById(R.id.prediction_bar)
        prediction1 = rootView.findViewById(R.id.prediction1)
        prediction2 = rootView.findViewById(R.id.prediction2)
        prediction3 = rootView.findViewById(R.id.prediction3)
        // Set click listeners for predictions
        prediction1.setOnClickListener { selectPrediction(prediction1.text.toString()) }
        prediction2.setOnClickListener { selectPrediction(prediction2.text.toString()) }
        prediction3.setOnClickListener { selectPrediction(prediction3.text.toString()) }

        // Load dictionary in background
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Load English dictionary
                val words = assets.open("english.txt")
                    .bufferedReader()
                    .readLines()
                    .map { it.trim().lowercase() }
                    .filter { it.isNotEmpty() }
                    .toSet()
                val prefixIndex = buildPrefixIndex(words)
                withContext(Dispatchers.Main) {
                    dictionary = words
                    prefixMap.clear()
                    prefixMap.putAll(prefixIndex)
                }
                // load Chinese dictionary
                val chineseMap = mutableMapOf<String, String>()
                assets.open("chinese.txt").bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.trim().split("\\s+".toRegex(), 2)
                        if (parts.size == 2) {
                            val pinyin = parts[0].trim()
                            val chinese = parts[1].trim()
                            chineseMap[pinyin] = chinese
                        }
                    }
                }
                withContext(Dispatchers.Main) {
                    chineseDictionary = chineseMap
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(keyboardView) { view, insets ->
            val navInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                navInset
            )
            insets
        }
        ViewCompat.requestApplyInsets(keyboardView)
        return rootView
    }

    private fun buildPrefixIndex(words: Set<String>): Map<String, List<String>> {
        val buckets = HashMap<String, MutableList<String>>()
        for (word in words) {
            val maxPrefix = min(4, word.length)
            for (i in 1..maxPrefix) {
                val prefix = word.substring(0, i)
                val bucket = buckets.getOrPut(prefix) { mutableListOf() }
                if (bucket.size < 25) {
                    bucket.add(word)
                }
            }
        }
        return buckets
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Ensure the IME window respects system insets and does not draw behind the navigation bar
        try {
            val window = window.window ?: return
            val decorView = window.decorView
            // Clear flags that allow layout behind system bars
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            // Add flag to adjust layout for system decor (navigation bar)
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)
            // Set stable layout to prevent UI shifts
            decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val currentKeyGroups = mutableListOf<String>()

    // On key press, add the key label (e.g., "qw", "er", etc.)
    fun onT13KeyPress(keyLabel: String) {
        // Add synchronously on Main thread — guaranteed before next onKey call
        currentKeyGroups.add(keyLabel)
        // Debounce only the expensive prediction update
        predictionJob?.cancel()
        predictionJob = serviceScope.launch {
            delay(50)
            updatePredictionBar()
        }
    }

    // Generate all possible combinations (cartesian product)
    fun getCombinations(groups: List<String>, limit: Int = 20): List<String> {
        if (groups.isEmpty()) return emptyList()
        var current = listOf("")
        for (group in groups) {
            val next = mutableListOf<String>()
            for (prefix in current) {
                for (c in group) {
                    next.add(prefix + c)
                    if (next.size >= limit) return next
                }
            }
            current = next
        }
        return current
    }

    // On backspace, remove last group
    fun onBackspace() {
        if (currentKeyGroups.isNotEmpty()) {
            currentKeyGroups.removeAt(currentKeyGroups.size - 1)
        }
        predictionJob?.cancel()
        predictionJob = serviceScope.launch {
            delay(50)
            updatePredictionBar()
        }
    }

    private fun getKeyLabel(code: Int): String? {
        return when (code) {
            113, 119 -> "qw"    // q or w
            101, 114 -> "er"    // e or r
            116, 121 -> "ty"    // t or y
            117, 105 -> "ui"    // u or i
            111, 112 -> "op"    // o or p
            97, 115  -> "as"    // a or s
            100, 102 -> "df"    // d or f
            103, 104 -> "gh"    // g or h
            106, 107 -> "jk"    // j or k
            108      -> "l"     // l
            122, 120 -> "zx"    // z or x
            99, 118  -> "cv"    // c or v
            98, 110  -> "bn"    // b or n
            109      -> "m"     // m
            else -> null
        }
    }

    // Update prediction bar and visibility
    private suspend fun updatePredictionBar() {

        if (isChinese && chineseDictionary.isNotEmpty() && chineseInput.isNotEmpty()) {
            val prefix = chineseInput.toString()
            val predictions = withContext(Dispatchers.Default) {
                chineseDictionary.filterKeys { it.startsWith(prefix) }.values.take(3)
            }
            prediction1.text = predictions.getOrNull(0) ?: ""
            prediction2.text = predictions.getOrNull(1) ?: ""
            prediction3.text = predictions.getOrNull(2) ?: ""
            predictionBar.visibility = if (predictions.isNotEmpty()) View.VISIBLE else View.GONE
        } else if (!isT13 || dictionary.isEmpty() || currentKeyGroups.isEmpty()) {
            predictionBar.visibility = View.GONE
            prediction1.text = ""
            prediction2.text = ""
            prediction3.text = ""
            return
        }

        val groups = currentKeyGroups.toList()

        val predictions = withContext(Dispatchers.Default) {
            val combos = getCombinations(groups, 200)
            val exactLength = groups.size
            // Find matches in dictionary in order of appearance
            val exactMatches = dictionary.filter { combos.contains(it) && it.length == exactLength }
            val shorterMatches = dictionary.filter { combos.contains(it) && it.length < exactLength && it !in exactMatches }
            val prefixMatches = mutableListOf<String>()
            for (combo in combos) {
                val matches = prefixMap[combo]
                if (matches != null) {
                    for (w in matches) {
                        if (w.length > exactLength && w !in exactMatches && w !in shorterMatches && w !in prefixMatches) {
                            prefixMatches.add(w)
                        }
                    }
                }
                if (exactMatches.size + shorterMatches.size + prefixMatches.size >= 3) break
            }
            (exactMatches + shorterMatches + prefixMatches).take(3)
        }

        prediction1.text = predictions.getOrNull(0) ?: ""
        prediction2.text = predictions.getOrNull(1) ?: ""
        prediction3.text = predictions.getOrNull(2) ?: ""
        predictionBar.visibility = if (predictions.isNotEmpty()) View.VISIBLE else View.GONE
    }

    // Select a prediction
    private fun selectPrediction(word: String) {
        if (word.isEmpty()) return
        val ic = currentInputConnection ?: return
        // Delete typed chars
        val deleteLength = if (isChinese) chineseInput.length else currentKeyGroups.size
        ic.deleteSurroundingText(deleteLength, 0)
        ic.commitText(word, 1)
        resetSequence()
    }

    // Commit top prediction
    private fun commitTopPrediction(ic: android.view.inputmethod.InputConnection) {
        val predictions = (getCombinations(currentKeyGroups).filter { dictionary.contains(it) } +
                dictionary.filter { word -> getCombinations(currentKeyGroups).any { combo -> word.startsWith(combo) } })
            .take(1)
        if (predictions.isNotEmpty()) {
            ic.deleteSurroundingText(currentKeyGroups.size, 0)
            ic.commitText(predictions[0], 1)
        }
    }

    // Reset sequence
    private fun resetSequence() {
        currentKeyGroups.clear()
        chineseInput.clear()
        predictionJob?.cancel()
        predictionJob = serviceScope.launch {
            updatePredictionBar()
        }
    }

    override fun onPress(primaryCode: Int) {}
    override fun onRelease(primaryCode: Int) {}
    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        Log.d("KeyboardService", "onKey called: primaryCode=$primaryCode")
        val ic = currentInputConnection ?: return
        when (primaryCode) {
            -5 -> { // Backspace
                if (isChinese) {
                    if (chineseInput.isNotEmpty()) {
                        chineseInput.deleteCharAt(chineseInput.length - 1)
                        predictionJob?.cancel()
                        predictionJob = serviceScope.launch {
                            delay(50)
                            updatePredictionBar()
                        }
                    } else {
                        ic.deleteSurroundingText(1, 0)
                    }
                } else {
                    if (currentKeyGroups.isNotEmpty()) {
                        currentKeyGroups.removeAt(currentKeyGroups.size - 1)
                    }
                    ic.deleteSurroundingText(1, 0)
                    predictionJob?.cancel()
                    predictionJob = serviceScope.launch {
                        delay(50)
                        updatePredictionBar()
                    }
                }
            }
            32 -> { // Space
                if (isChinese) {
                    val word = prediction1.text.toString()
                    if (word.isNotEmpty()) {
                        ic.deleteSurroundingText(chineseInput.length, 0)
                        ic.commitText(word, 1)
                    } else {
                        ic.commitText(chineseInput.toString(), 1)
                    }
                    ic.commitText(" ", 1)
                    chineseInput.clear()
                    predictionBar.visibility = View.GONE
                } else {
                    // Commit the word shown in the prediction bar if available
                    val word = prediction1.text.toString()
                    if (isT13 && word.isNotEmpty()) {
                        ic.deleteSurroundingText(currentKeyGroups.size, 0)
                        ic.commitText(word, 1)
                    } else {
                        commitTopPrediction(ic)
                    }
                    ic.commitText(" ", 1)
                    resetSequence()
                }
            }
            -4 -> { // Enter
                commitTopPrediction(ic)
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                resetSequence()

            }
            -101 -> {
                keyboard = Keyboard(this, R.xml.qwerty)
                keyboardView.keyboard = keyboard
                isT13 = false
                keyboardView.bypassTouchHandling = false
                resetSequence()

            }
            -102 -> {
                keyboard = Keyboard(this, R.xml.t13)
                keyboardView.keyboard = keyboard
                isT13 = true
                keyboardView.bypassTouchHandling = true
                resetSequence()

            }
            -103 -> { // Example code for Chinese layout
                keyboard = Keyboard(this, R.xml.chinese_full)
                keyboardView.keyboard = keyboard
                isChinese = true
                keyboardView.bypassTouchHandling = false
                resetSequence()
            }
            else -> {

                if (isChinese) {
                    val char = primaryCode.toChar()
                    if (char.isLetter()) {
                        chineseInput.append(char)
                        predictionJob?.cancel()
                        predictionJob = serviceScope.launch {
                            delay(50)
                            updatePredictionBar()
                        }
                    }
                } else if (isT13) {
                    val keyLabel = getKeyLabel(primaryCode) ?: return
                    onT13KeyPress(keyLabel)
                    ic.commitText(keyLabel.first().toString(),1)
                } else {
                    ic.commitText(primaryCode.toChar().toString(), 1)
                }
            }
        }
    }

    override fun onText(text: CharSequence?) {}
    override fun swipeLeft() {}
    override fun swipeRight() {}
    override fun swipeDown() {}
    override fun swipeUp() {}
}
