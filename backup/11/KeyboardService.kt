package com.mtedwin.ekeyboard

import android.content.ContentValues.TAG
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.Keyboard
import android.inputmethodservice.KeyboardView
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.*
import kotlin.math.min
import android.widget.PopupWindow
import androidx.annotation.RequiresApi
import androidx.core.view.WindowInsetsControllerCompat

import java.io.File
import kotlin.collections.get
import kotlin.compareTo
import kotlin.dec

class KeyboardService : InputMethodService(), KeyboardView.OnKeyboardActionListener {
    companion object {
        private const val MAX_INPUT_LENGTH = 5  // Maximum 5 inputs for Chinese/T13C
    }
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

    private var isQuickChi : Boolean = false
    private var isT13C: Boolean = false
    private var isSymbol: Boolean = false
    private var chineseDictionary: Map<String, List<String>> = emptyMap()
    private var chineseCharacterRank: Map<String, Int> = emptyMap() // Track frequency rank from CSV
    private var backspaceJob: Job? = null
    private var currentChinesePinyinLength = 0
    private var inputStartPosition: Int = -1
    private var keyLabelPopup: PopupWindow? = null
    private var isCapsNext: Boolean = false // Track if next letter should be capitalized
    private var engFileObserver: FileObserver? = null
    private var chiFileObserver: FileObserver? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate() {
        super.onCreate()
        val englishFile = File(filesDir, "english.txt")
        val chineseFile = File(filesDir, "chinese.csv")

        engFileObserver = @RequiresApi(Build.VERSION_CODES.Q)
        object : FileObserver(englishFile, FileObserver.MODIFY or FileObserver.CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (event == FileObserver.MODIFY || event == FileObserver.CREATE) {
                    Log.d("KeyboardService", "english.txt changed, reloading dictionary")
                    reloadDictionaries()
                }
            }
        }
        chiFileObserver = @RequiresApi(Build.VERSION_CODES.Q)
        object : FileObserver(chineseFile, FileObserver.MODIFY or FileObserver.CREATE) {
            override fun onEvent(event: Int, path: String?) {
                if (event == FileObserver.MODIFY || event == FileObserver.CREATE) {
                    Log.d("KeyboardService", "chinese.csv changed, reloading dictionary")
                    reloadDictionaries()
                }
            }
        }
        engFileObserver?.startWatching()
        chiFileObserver?.startWatching()


//        val filter = android.content.IntentFilter("com.mtedwin.ekeyboard.ACTION_RELOAD_DICTIONARY")
//        applicationContext.registerReceiver(reloadReceiver, filter, RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {

        engFileObserver?.stopWatching()
        chiFileObserver?.stopWatching()
//        unregisterReceiver(reloadReceiver)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val rootView = layoutInflater.inflate(R.layout.keyboard_view, null) as LinearLayout
        keyboardView = rootView.findViewById(R.id.keyboard_view)
        keyboard = Keyboard(this,
            when {
                isT13 -> R.xml.t13
                isT13C -> R.xml.t13c
                isSymbol -> R.xml.symbol
                else -> R.xml.qwerty
            }
        )
        keyboardView.keyboard = keyboard
        keyboardView.setOnKeyboardActionListener(this)
        keyboardView.isPreviewEnabled = false
        keyboardView.bypassTouchHandling = isT13 || isT13C

        keyboardView.onKeyLongPressListener =
            object : NoDoubleTapKeyboardView.OnKeyLongPressListener {
                override fun onKeyLongPressed(key: Keyboard.Key) {
                    Log.d(TAG, "Long press---on key: ${key.codes[0]}")
                    when (key.codes[0]) {
                        -5 -> { // Backspace
                            backspaceJob?.cancel()
                            backspaceJob = serviceScope.launch {
                                while (isActive) {
                                    if (currentKeyGroups.isNotEmpty()) {
                                        currentKeyGroups.removeAt(currentKeyGroups.size - 1)
                                        if ((isChinese || isT13C) && currentChinesePinyinLength > 0) {
                                            currentChinesePinyinLength--
                                        }
                                        predictionJob?.cancel()
                                        predictionJob = launch {
                                            delay(50)
                                            updatePredictionBar()
                                        }
                                    } else {
                                        currentInputConnection?.deleteSurroundingText(1, 0)
                                    }
                                    delay(100) // Adjust speed (lower = faster)
                                }
                            }
                        }
                        -7 -> {
                            if (isQuickChi){
                                isQuickChi = false
                                key.label = "速✗"
                            } else {
                                isQuickChi = true
                                key.label = "速✔"
                            }

                        }

                    }
                    // Do something on long press, e.g.:
                    // when (key.codes[0]) { ... }
                }

                override fun onKeyReleased(primaryCode: Int) {
                    Log.d(TAG, "Long press---on key: canceled for key is calling: $primaryCode")

                    if (primaryCode == -5) {
                        Log.d(TAG, "Long press---on key: canceled for key: $primaryCode")
                        backspaceJob?.cancel()
                        backspaceJob = null
                    }
                }
            }

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
        reloadDictionaries()

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
        try {
            val window = window.window ?: return
            val decorView = window.decorView

            // Clear flags that allow layout behind system bars
            window.clearFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)

            // Add flag to adjust layout for system decor (navigation bar)
            window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR)

            // Use WindowInsetsControllerCompat instead of deprecated systemUiVisibility
            val insetsController = WindowInsetsControllerCompat(window, decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            // Force reapply insets multiple times to ensure they take effect
            ViewCompat.requestApplyInsets(decorView)
            decorView.post {
                ViewCompat.requestApplyInsets(decorView)
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    val currentKeyGroups = mutableListOf<String>()

    // On key press, add the key label (e.g., "qw", "er", etc.)
    fun onT13KeyPress(keyLabel: String) {
        // For T13C mode, ignore inputs beyond MAX_INPUT_LENGTH
        if (isT13C && currentKeyGroups.size >= MAX_INPUT_LENGTH) {
            return
        }

        if (currentKeyGroups.isEmpty()) {
            val ic = currentInputConnection ?: return
            inputStartPosition = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
        }

        // Add synchronously on Main thread — guaranteed before next onKey call
        currentKeyGroups.add(keyLabel)
        showKeyLabelPopup(getKeyGroupsDisplayString())


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

    // Get single Chinese character radical for Chinese keyboard mode
    private fun getChineseLabel(code: Int): String? {
        return when (code) {
            113 -> "手"    // q
            119 -> "田"    // w
            101 -> "水"    // e
            114 -> "口"    // r
            116 -> "廿"    // t
            121 -> "卜"    // y
            117 -> "山"    // u
            105 -> "戈"    // i
            111 -> "人"    // o
            112 -> "心"    // p
            97  -> "日"    // a
            115 -> "尸"    // s
            100 -> "木"    // d
            102 -> "火"    // f
            103 -> "土"    // g
            104 -> "竹"    // h
            106 -> "十"    // j
            107 -> "大"    // k
            108 -> "中"    // l
            122 -> "重"    // z
            120 -> "難"    // x
            99  -> "金"    // c
            118 -> "女"    // v
            98  -> "月"    // b
            110 -> "弓"    // n
            109 -> "一"    // m
            else -> null
        }
    }

    // Convert key groups to display string based on mode
    private fun getKeyGroupsDisplayString(): String {
        if (isT13C) {
            // For T13C, show Chinese radicals separated by commas
            return currentKeyGroups.joinToString(", ") { keyGroup ->
                // keyGroup is like "qw", "er", etc.
                // Get the first character to map to radical
                val firstChar = keyGroup.firstOrNull()?.code ?: return@joinToString keyGroup
                getT13CLabel(firstChar) ?: keyGroup
            }
        } else if (isChinese) {
            // For Chinese mode, show Chinese radicals separated by commas
            return currentKeyGroups.joinToString(", ") { keyGroup ->
                // keyGroup is a single character like "n", "i", "h", "a", "o"
                val charCode = keyGroup.firstOrNull()?.code ?: return@joinToString keyGroup
                getChineseLabel(charCode) ?: keyGroup
            }
        } else {
            // For T13 mode, show the original format
            return currentKeyGroups.toString()
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
            // Always show the bar in Chinese mode
            predictionBar.visibility = View.VISIBLE
            return
        }
        if (isT13C) {
            // T13C mode: generate combinations from key groups, then lookup Chinese characters
            // Show 6 predictions for T13C
            prediction4.visibility = View.VISIBLE
            prediction5.visibility = View.VISIBLE
            prediction6.visibility = View.VISIBLE

            if (currentKeyGroups.isEmpty()) {
                predictionBar.visibility = View.VISIBLE // Always show prediction bar for T13C
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
                val seenChars = mutableSetOf<String>()

                // Collect all matching characters from all combinations
                for (combo in combos) {
                    val matches = chineseDictionary[combo]
                    if (matches != null) {
                        seenChars.addAll(matches)
                    }
                }

                // Sort by frequency rank (lower rank = more common = appears earlier in CSV)
                val sorted = seenChars.sortedBy { char ->
                    chineseCharacterRank[char] ?: Int.MAX_VALUE
                }

                sorted.take(6)
            }
            prediction1.text = predictions.getOrNull(0) ?: ""
            prediction2.text = predictions.getOrNull(1) ?: ""
            prediction3.text = predictions.getOrNull(2) ?: ""
            prediction4.text = predictions.getOrNull(3) ?: ""
            prediction5.text = predictions.getOrNull(4) ?: ""
            prediction6.text = predictions.getOrNull(5) ?: ""
            predictionBar.visibility = View.VISIBLE // Always show prediction bar for T13C
            return
        }
        if (!isT13 || dictionary.isEmpty()) {
            // Hide extra predictions for T13 mode (only show 3)
            prediction4.visibility = View.GONE
            prediction5.visibility = View.GONE
            prediction6.visibility = View.GONE

            predictionBar.visibility = View.VISIBLE // Always show prediction bar for T13
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

        prediction1.text = predictions.getOrNull(0)?.let {
            if (isCapsNext && it.isNotEmpty()) it.replaceFirstChar { c -> c.uppercase() } else it
        } ?: ""
        prediction2.text = predictions.getOrNull(1)?.let {
            if (isCapsNext && it.isNotEmpty()) it.replaceFirstChar { c -> c.uppercase() } else it
        } ?: ""
        prediction3.text = predictions.getOrNull(2)?.let {
            if (isCapsNext && it.isNotEmpty()) it.replaceFirstChar { c -> c.uppercase() } else it
        } ?: ""
        predictionBar.visibility = View.VISIBLE // Always show prediction bar for T13

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
        isCapsNext = false // Reset caps BEFORE resetSequence so updatePredictionBar doesn't capitalize next predictions
        ic.commitText(word, 1)

        // Add space after word in T13 mode if setting is enabled
        if (isT13) {
            val sharedPreferences = getSharedPreferences("KeyboardSettings", MODE_PRIVATE)
            val t13AutoSpaceEnabled = sharedPreferences.getBoolean("t13AutoSpace", false)
            if (t13AutoSpaceEnabled) {
                ic.commitText(" ", 1)
            }
        }

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
            -7 -> {}
            -5 -> { // Backspace
                if (currentKeyGroups.isNotEmpty()) {
                    currentKeyGroups.removeAt(currentKeyGroups.size - 1)
                    if ((isChinese || isT13C) && currentChinesePinyinLength > 0) {
                        currentChinesePinyinLength--
                    }
                    if ((isChinese || isT13 || isT13C) && currentKeyGroups.isNotEmpty()) {
                        // Show current key groups after backspace
                        showKeyLabelPopup(getKeyGroupsDisplayString())
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
                // Read auto-space setting once for this handler
                val sharedPreferences = getSharedPreferences("KeyboardSettings", MODE_PRIVATE)
                val t13AutoSpaceEnabled = sharedPreferences.getBoolean("t13AutoSpace", false)

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
                    isCapsNext = false // Reset caps BEFORE resetSequence
                    ic.commitText(word, 1)

                    // Only add space if auto-space is enabled
                    if (t13AutoSpaceEnabled) {
                        ic.commitText(" ", 1)
                    }

                    resetSequence()
                    return // Exit early to avoid adding another space at the end
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
            -6 -> { // Caps button
                isCapsNext = !isCapsNext
                // Update prediction bar if in T13 mode to show capitalized predictions
                if (isT13) {
                    predictionJob?.cancel()
                    predictionJob = serviceScope.launch {
                        updatePredictionBar()
                    }
                }
            }
            -101 -> {
                keyboard = Keyboard(this, R.xml.qwerty)
                keyboardView.keyboard = keyboard
                isT13 = false
                isChinese = false
                isT13C = false
                isSymbol = false
                keyboardView.bypassTouchHandling = true
                resetSequence()

            }
            -102 -> {
                keyboard = Keyboard(this, R.xml.t13)
                keyboardView.keyboard = keyboard
                isT13 = true
                isChinese = false
                isT13C = false
                isSymbol = false
                keyboardView.bypassTouchHandling = true
                resetSequence()

            }
            -103 -> { // Chinese layout
                keyboard = Keyboard(this, R.xml.chinese_full)
                keyboardView.keyboard = keyboard
                isChinese = true
                isT13 = false
                isT13C = false
                isSymbol = false
                keyboardView.bypassTouchHandling = true
                resetSequence()
            }
            -104 -> { // T13C layout - combines T13 key grouping with Chinese dictionary
                keyboard = Keyboard(this, R.xml.t13c)
                keyboardView.keyboard = keyboard
                isT13C = true
                isT13 = false
                isChinese = false
                isSymbol = false
                keyboardView.bypassTouchHandling = true
                resetSequence()
            }
            -105 -> { // Symbol keyboard
                keyboard = Keyboard(this, R.xml.symbol)
                keyboardView.keyboard = keyboard
                isSymbol = true
                isT13 = false
                isT13C = false
                isChinese = false
                keyboardView.bypassTouchHandling = true
                resetSequence()
            }
            -106 -> { // Cycle through app keyboards: QWERTY → T13 → Chinese Full → T13C → QWERTY
                when {
                    !isT13 && !isChinese && !isT13C && !isSymbol -> {
                        // Currently QWERTY → Switch to T13
                        keyboard = Keyboard(this, R.xml.t13)
                        keyboardView.keyboard = keyboard
                        isT13 = true
                        isChinese = false
                        isT13C = false
                        isSymbol = false
                        keyboardView.bypassTouchHandling = true
                        resetSequence()
                    }
                    isT13 -> {
                        // Currently T13 → Switch to Chinese Full
                        keyboard = Keyboard(this, R.xml.chinese_full)
                        keyboardView.keyboard = keyboard
                        isChinese = true
                        isT13 = false
                        isT13C = false
                        isSymbol = false
                        keyboardView.bypassTouchHandling = true
                        resetSequence()
                    }
                    isChinese -> {
                        // Currently Chinese Full → Switch to T13C
                        keyboard = Keyboard(this, R.xml.t13c)
                        keyboardView.keyboard = keyboard
                        isT13C = true
                        isT13 = false
                        isChinese = false
                        isSymbol = false
                        keyboardView.bypassTouchHandling = true
                        resetSequence()
                    }
                    isT13C -> {
                        // Currently T13C → Switch to QWERTY
                        keyboard = Keyboard(this, R.xml.qwerty)
                        keyboardView.keyboard = keyboard
                        isT13 = false
                        isChinese = false
                        isT13C = false
                        isSymbol = false
                        keyboardView.bypassTouchHandling = true
                        resetSequence()
                    }
                }
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
                    // For Chinese mode, ignore inputs beyond MAX_INPUT_LENGTH
                    if (currentKeyGroups.size >= MAX_INPUT_LENGTH) {
                        return
                    }

                    if (currentKeyGroups.isEmpty()) {
                        inputStartPosition = ic.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)?.selectionStart ?: 0
                    }

                    // Accumulate input for Chinese, do not commit Latin char
                    val keyChar = primaryCode.toChar().toString()
                    currentKeyGroups.add(keyChar)
                    showKeyLabelPopup(getKeyGroupsDisplayString())
                    currentChinesePinyinLength++


                    //ic.commitText(" ",1)
                    predictionJob?.cancel()
                    predictionJob = serviceScope.launch {
                        delay(50)
                        updatePredictionBar()
                    }
                } else {
                    // QWERTY mode - handle caps for letters
                    val char = primaryCode.toChar()
                    val textToCommit = if (isCapsNext && char.isLetter()) {
                        isCapsNext = false // Reset caps after typing one letter
                        char.uppercaseChar().toString()
                    } else {
                        char.toString()
                    }
                    ic.commitText(textToCommit, 1)
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

        // Read popup position from SharedPreferences (default -300)
        val sharedPreferences = getSharedPreferences("KeyboardSettings", MODE_PRIVATE)
        val popupPosition = sharedPreferences.getInt("popupPosition", -300)

        keyLabelPopup = PopupWindow(textView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isOutsideTouchable = true
            showAtLocation(keyboardView, android.view.Gravity.TOP, 0, popupPosition)
            // Auto-dismiss after 1 second
            Handler(Looper.getMainLooper()).postDelayed({ dismiss() }, 2000)
        }
    }

    public fun reloadDictionaries() {
        serviceScope.launch(Dispatchers.IO){
            try {
                // Load English dictionary
                val words = File(filesDir, "english.txt")
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
                // Preserve order from CSV (frequency-based, most common first)
                val chineseWords = File(filesDir, "chinese.csv")
                    .bufferedReader()
                    .useLines { lines ->
                        val result = LinkedHashMap<String, MutableList<String>>()
                        val rankMap = mutableMapOf<String, Int>()
                        var lineNumber = 0
                        lines.forEach { line ->
                            val cleaned = line.trim().removePrefix("\uFEFF")
                            if (cleaned.isEmpty()) return@forEach
                            val parts = cleaned.split(',')
                            if (parts.size != 2) return@forEach
                            val character = parts[0].trim()
                            val input = parts[1].trim()
                            if (character.isEmpty() || input.isEmpty()) return@forEach
                            // Track the rank (line number) for each character
                            if (!rankMap.containsKey(character)) {
                                rankMap[character] = lineNumber
                            }
                            // Preserve order by adding to list in the order they appear in CSV
                            result.getOrPut(input) { mutableListOf() }.add(character)
                            lineNumber++
                        }
                        Pair(result.mapValues { it.value.toList() }, rankMap)
                    }
                withContext(Dispatchers.Main) {
                    chineseDictionary = chineseWords.first
                    chineseCharacterRank = chineseWords.second
                }
                Log.d("KeyboardService", "${chineseDictionary} Chinese entries")

                Log.d("KeyboardService", "Dictionaries loaded: ${dictionary.size} English words, ${chineseDictionary.size} Chinese entries")

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

