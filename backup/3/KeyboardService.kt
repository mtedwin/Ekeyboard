package com.mtedwin.ekeyboard

import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.toString
import android.widget.PopupWindow


class KeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {
    private lateinit var keyboardView: NoDoubleTapKeyboardView
    private lateinit var keyboard: Keyboard
    private var isT13: Boolean = true
    private lateinit var predictionBar: LinearLayout
    private lateinit var prediction1: TextView
    private lateinit var prediction2: TextView
    private lateinit var prediction3: TextView
    private lateinit var prediction4: TextView
    private lateinit var prediction5: TextView
    private lateinit var prediction6: TextView
    private var dictionary: Set<String> = emptySet()
    private val prefixMap = mutableMapOf<String, List<String>>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var predictionJob: Job? = null
    private var isChinese: Boolean = false
    private var isT13C: Boolean = false
    private var chineseDictionary: Map<String, List<String>> = emptyMap()
    private var backspaceJob: Job? = null
    private var currentChinesePinyinLength = 0
    private var inputStartPosition: Int = -1
    private var keyLabelPopup: PopupWindow? = null


    override fun onCreateInputView(): View {
        val rootView = layoutInflater.inflate(R.layout.keyboard_view, null) as LinearLayout
        keyboardView = rootView.findViewById(R.id.keyboard_view)
        keyboard = Keyboard(this, if (isT13) R.xml.t13 else if (isT13C) R.xml.t13c else R.xml.qwerty)
        keyboardView.keyboard = keyboard
        keyboardView.setOnKeyboardActionListener(this)
        keyboardView.isPreviewEnabled = false
        keyboardView.bypassTouchHandling = isT13 || isT13C

        predictionBar = rootView.findViewById(R.id.prediction_bar)
        prediction1 = rootView.findViewById(R.id.prediction1)
        prediction2 = rootView.findViewById(R.id.prediction2)
        prediction3 = rootView.findViewById(R.id.prediction3)
        prediction4 = rootView.findViewById(R.id.prediction4)
        prediction5 = rootView.findViewById(R.id.prediction5)
        prediction6 = rootView.findViewById(R.id.prediction6)
        // Set click listeners for predictions
        prediction1.setOnClickListener { selectPrediction(prediction1.text.toString()) }
        prediction2.setOnClickListener { selectPrediction(prediction2.text.toString()) }
        prediction3.setOnClickListener { selectPrediction(prediction3.text.toString()) }
        prediction4.setOnClickListener { selectPrediction(prediction4.text.toString()) }
        prediction5.setOnClickListener { selectPrediction(prediction5.text.toString()) }
        prediction6.setOnClickListener { selectPrediction(prediction6.text.toString()) }

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
                val chineseWords = assets.open("chinese.csv")
                    .bufferedReader()
                    .useLines { lines ->
                        lines.mapNotNull { line ->
                            val cleaned = line.trim().removePrefix("\uFEFF")
                            if (cleaned.isEmpty()) return@mapNotNull null
                            val parts = cleaned.split(',')
                            if (parts.size != 2) return@mapNotNull null
                            val character = parts[0].trim()
                            val input = parts[1].trim()
                            if (character.isEmpty() || input.isEmpty()) null else input to character
                        }.groupBy({ it.first }, { it.second })
                    }
                withContext(Dispatchers.Main) {
                    chineseDictionary = chineseWords
                }
                Log.d("KeyboardService", "${chineseDictionary} Chinese entries")

                Log.d("KeyboardService", "Dictionaries loaded: ${dictionary.size} English words, ${chineseDictionary.size} Chinese entries")

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
        if (currentKeyGroups.isEmpty()) {
            val ic = currentInputConnection ?: return
            inputStartPosition = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
        }
        // Add synchronously on Main thread — guaranteed before next onKey call
        currentKeyGroups.add(keyLabel)
        showKeyLabelPopup(currentKeyGroups.toString())
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

    // Get T13C Chinese radical label for display
    private fun getT13CLabel(code: Int): String? {
        return when (code) {
            113, 119 -> "手田"    // q or w
            101, 114 -> "水口"    // e or r
            116, 121 -> "廿卜"    // t or y
            117, 105 -> "山戈"    // u or i
            111, 112 -> "人心"    // o or p
            97, 115  -> "日尸"    // a or s
            100, 102 -> "木火"    // d or f
            103, 104 -> "土竹"    // g or h
            106, 107 -> "十大"    // j or k
            108      -> "中"      // l
            122, 120 -> "難"      // z or x
            99, 118  -> "金女"    // c or v
            98, 110  -> "月弓"    // b or n
            109      -> "一"      // m
            else -> null
        }
    }

    // Update prediction bar and visibility
    private suspend fun updatePredictionBar() {
        if (isChinese) {
            // Chinese mode: direct pinyin lookup (e.g., "ni" -> "你", "好" etc)
            // Hide extra predictions for Chinese mode (only show 3)
            prediction4.visibility = View.GONE
            prediction5.visibility = View.GONE
            prediction6.visibility = View.GONE

            val input = currentKeyGroups.joinToString("")
            val predictions = chineseDictionary[input] ?: emptyList()
            prediction1.text = predictions.getOrNull(0) ?: ""
            prediction2.text = predictions.getOrNull(1) ?: ""
            prediction3.text = predictions.getOrNull(2) ?: ""
            // Always show the bar in Chinese mode if there is input
            predictionBar.visibility = if (currentKeyGroups.isNotEmpty()) View.VISIBLE else View.GONE
            return
        }
        if (isT13C) {
            // T13C mode: generate combinations from key groups, then lookup Chinese characters
            // Show 6 predictions for T13C
            prediction4.visibility = View.VISIBLE
            prediction5.visibility = View.VISIBLE
            prediction6.visibility = View.VISIBLE

            if (currentKeyGroups.isEmpty()) {
                predictionBar.visibility = View.GONE
                prediction1.text = ""
                prediction2.text = ""
                prediction3.text = ""
                prediction4.text = ""
                prediction5.text = ""
                prediction6.text = ""
                return
            }
            val groups = currentKeyGroups.toList()
            val predictions = withContext(Dispatchers.Default) {
                val combos = getCombinations(groups, 200)
                val results = mutableListOf<String>()
                // Look up each combination in the Chinese dictionary
                for (combo in combos) {
                    val matches = chineseDictionary[combo]
                    if (matches != null) {
                        for (char in matches) {
                            if (char !in results) {
                                results.add(char)
                                if (results.size >= 6) break
                            }
                        }
                    }
                    if (results.size >= 6) break
                }
                results.take(6)
            }
            prediction1.text = predictions.getOrNull(0) ?: ""
            prediction2.text = predictions.getOrNull(1) ?: ""
            prediction3.text = predictions.getOrNull(2) ?: ""
            prediction4.text = predictions.getOrNull(3) ?: ""
            prediction5.text = predictions.getOrNull(4) ?: ""
            prediction6.text = predictions.getOrNull(5) ?: ""
            predictionBar.visibility = if (predictions.isNotEmpty()) View.VISIBLE else View.GONE
            return
        }
        if (!isT13 || dictionary.isEmpty() || currentKeyGroups.isEmpty()) {
            // Hide extra predictions for T13 mode (only show 3)
            prediction4.visibility = View.GONE
            prediction5.visibility = View.GONE
            prediction6.visibility = View.GONE

            predictionBar.visibility = View.GONE
            prediction1.text = ""
            prediction2.text = ""
            prediction3.text = ""
            return
        }

        // T13 mode: Hide extra predictions (only show 3)
        prediction4.visibility = View.GONE
        prediction5.visibility = View.GONE
        prediction6.visibility = View.GONE

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
        if (inputStartPosition != -1) {
            val currentPos = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
            val lengthToDelete = currentPos - inputStartPosition
            if (lengthToDelete > 0) {
                ic.deleteSurroundingText(lengthToDelete, 0)
            }
        }
        ic.commitText(word, 1)
        resetSequence()
    }

    // Commit top prediction
    private fun commitTopPrediction(ic: android.view.inputmethod.InputConnection) {
        val predictions = (getCombinations(currentKeyGroups).filter { dictionary.contains(it) } +
                dictionary.filter { word -> getCombinations(currentKeyGroups).any { combo -> word.startsWith(combo) } })
            .take(1)
        if (predictions.isNotEmpty()) {
            if (inputStartPosition != -1) {
                val currentPos = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
                val lengthToDelete = currentPos - inputStartPosition
                if (lengthToDelete > 0) {
                    ic.deleteSurroundingText(lengthToDelete, 0)
                }
            }
            ic.commitText(predictions[0], 1)
        }
    }

    private fun handleBackspace() {
        val ic = currentInputConnection ?: return
        ic.deleteSurroundingText(1, 0)
    }

    // Reset sequence
    private fun resetSequence() {
        currentKeyGroups.clear()
        if (isChinese || isT13C) {
            currentChinesePinyinLength = 0
        }
        inputStartPosition = -1
        predictionJob?.cancel()
        predictionJob = serviceScope.launch {
            updatePredictionBar()
        }
    }

    override fun onPress(primaryCode: Int) {
//        if (primaryCode == -5) {
//            backspaceJob?.cancel()
//            backspaceJob = serviceScope.launch {
//                while (isActive) {
//                    handleBackspace()
//                    delay(300) // Adjust speed as needed
//                }
//            }
//        }
    }
    override fun onRelease(primaryCode: Int) {
//        if (primaryCode == -5) {
//            backspaceJob?.cancel()
//            backspaceJob = null
//            handleBackspace()
//        }
    }
    override fun onKey(primaryCode: Int, keyCodes: IntArray?) {
        Log.d("KeyboardService", "onKey called: primaryCode=$primaryCode")
        val ic = currentInputConnection ?: return
        when (primaryCode) {
            44 -> ic.commitText(",", 1) // Comma
            46 -> ic.commitText(".", 1) // Period
            in 48..57 -> { // Number keys 0-9
                ic.commitText(primaryCode.toChar().toString(), 1)
            }
            -5 -> { // Backspace
                if (currentKeyGroups.isNotEmpty()) {
                    currentKeyGroups.removeAt(currentKeyGroups.size - 1)
                    if ((isChinese || isT13C) && currentChinesePinyinLength > 0) {
                        currentChinesePinyinLength--
                    }
                    if ((isChinese || isT13 || isT13C) && currentKeyGroups.isNotEmpty()) {
                        // Show current key groups after backspace
                        showKeyLabelPopup(currentKeyGroups.toString())
                    }

                    predictionJob?.cancel()
                    predictionJob = serviceScope.launch {
                        delay(50)
                        updatePredictionBar()
                    }
                } else{
                    ic.deleteSurroundingText(1, 0)
                }




            }
            32 -> { // Space
                val word = prediction1.text.toString()
                if ((isChinese || isT13C) && word.isNotEmpty()) {
                    if (inputStartPosition != -1) {
                        val currentPos = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
                        val lengthToDelete = currentPos - inputStartPosition
                        if (lengthToDelete > 0) {
                            ic.deleteSurroundingText(lengthToDelete, 0)
                        }
                    }
                    ic.commitText(word, 1)
                    resetSequence()
                    currentChinesePinyinLength = 0
                    currentKeyGroups.clear() // Clear input for next char
                } else if (isT13 && word.isNotEmpty()) {
                    if (inputStartPosition != -1) {
                        val currentPos = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
                        val lengthToDelete = currentPos - inputStartPosition
                        if (lengthToDelete > 0) {
                            ic.deleteSurroundingText(lengthToDelete, 0)
                        }
                    }
                    ic.commitText(word, 1)
                    resetSequence()
                } else {
                    commitTopPrediction(ic)
                    resetSequence()
                }

                if (((isChinese || isT13C) && !word.isNotEmpty()) || (isT13 && !word.isNotEmpty()) || (!isChinese && !isT13 && !isT13C)) {
                    ic.commitText(" ", 1)
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
                isChinese = false
                isT13C = false
                keyboardView.bypassTouchHandling = false
                resetSequence()

            }
            -102 -> {
                keyboard = Keyboard(this, R.xml.t13)
                keyboardView.keyboard = keyboard
                isT13 = true
                isChinese = false
                isT13C = false
                keyboardView.bypassTouchHandling = true
                resetSequence()

            }
            -103 -> { // Chinese layout
                keyboard = Keyboard(this, R.xml.chinese_full)
                keyboardView.keyboard = keyboard
                isChinese = true
                isT13 = false
                isT13C = false
                keyboardView.bypassTouchHandling = false
                resetSequence()
            }
            -104 -> { // T13C layout - combines T13 key grouping with Chinese dictionary
                keyboard = Keyboard(this, R.xml.t13c)
                keyboardView.keyboard = keyboard
                isT13C = true
                isT13 = false
                isChinese = false
                keyboardView.bypassTouchHandling = true
                resetSequence()
            }
            else -> {
                if (isT13) {
                    val keyLabel = getKeyLabel(primaryCode) ?: return
                    onT13KeyPress(keyLabel)
                    //ic.commitText(" ",1)
                } else if (isT13C) {
                    // T13C: Works exactly like T13, but uses Chinese dictionary instead of English
                    val keyLabel = getKeyLabel(primaryCode) ?: return
                    onT13KeyPress(keyLabel)
                    // The difference is in updatePredictionBar() which uses chineseDictionary for T13C
                } else if (isChinese) {
                    if (currentKeyGroups.isEmpty()) {
                        inputStartPosition = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
                    }
                    // Accumulate input for Chinese, do not commit Latin char
                    val keyChar = primaryCode.toChar().toString()
                    currentKeyGroups.add(keyChar)
                    showKeyLabelPopup(currentKeyGroups.toString())
                    currentChinesePinyinLength++
                    //ic.commitText(" ",1)
                    predictionJob?.cancel()
                    predictionJob = serviceScope.launch {
                        delay(50)
                        updatePredictionBar()
                    }
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

    private fun showKeyLabelPopup(label: String) {
        val textView = TextView(this).apply {
            text = label
            setBackgroundColor(0xFF000000.toInt()) // Black background
            setTextColor(0xFFFFFFFF.toInt()) // White text
            setPadding(16, 8, 16, 8)
            textSize = 14f
        }
        keyLabelPopup?.dismiss()
        keyLabelPopup = PopupWindow(textView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            showAtLocation(keyboardView, android.view.Gravity.TOP ,0, -200)
            // Auto-dismiss after 1 second
            Handler(Looper.getMainLooper()).postDelayed({ dismiss() }, 2000)
        }
    }
}
