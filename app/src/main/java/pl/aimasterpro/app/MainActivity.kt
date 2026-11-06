package pl.aimasterpro.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.URLSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import pl.aimasterpro.app.agents.AgentPrompts
import pl.aimasterpro.app.agents.AgentCatalog
import pl.aimasterpro.app.agents.AgentProviderRouting
import pl.aimasterpro.app.agents.TeamRunEngine
import pl.aimasterpro.app.agents.TeamTask
import pl.aimasterpro.app.core.AgentRole
import pl.aimasterpro.app.core.FreeModelsCatalog
import pl.aimasterpro.app.core.ChatResponse
import pl.aimasterpro.app.core.ProviderConfig
import pl.aimasterpro.app.core.ProviderKind
import pl.aimasterpro.app.core.TokenTracker
import pl.aimasterpro.app.core.WorkMode
import pl.aimasterpro.app.data.BackupCrypto
import pl.aimasterpro.app.core.CompanionTrustRecovery
import pl.aimasterpro.app.data.SecureStore
import pl.aimasterpro.app.network.ProviderClient
import pl.aimasterpro.app.network.ProviderException
import pl.aimasterpro.app.network.ProviderModelInfo
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class MainActivity : Activity() {
    // ---- Paleta (ciemny motyw w stylu ChatGPT; pomarańcz/turkus zostają jako akcenty) ----
    private val bg = Color.rgb(33, 33, 33)
    private val sidebarBg = Color.rgb(23, 23, 23)
    private val panel = Color.rgb(47, 47, 47)
    private val cardBg = Color.rgb(38, 38, 38)
    private val border = Color.rgb(64, 64, 64)
    private val codeBg = Color.rgb(13, 13, 13)
    private val orange = Color.rgb(255, 107, 0)
    private val cyan = Color.rgb(49, 215, 208)
    private val white = Color.rgb(236, 236, 236)
    private val muted = Color.rgb(155, 155, 155)
    private val markdownLinkRegex = Regex("\\[([^\\]]+)]\\((https?://[^\\s)]+)\\)")

    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var store: SecureStore
    private lateinit var client: ProviderClient
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var screen = "Czat"
    private var selectedAgent = AgentRole.MASTER
    private var selectedProviderId = "gemini"
    private val messages = mutableListOf<Pair<String, String>>()
    /** Stable request IDs aligned with assistant messages; legacy chats may have no IDs. */
    private val assistantTaskIds = mutableListOf<String>()
    private val modelCatalogPrefs by lazy { getSharedPreferences("model_catalog_v1", Context.MODE_PRIVATE) }
    private val agentActivity = mutableListOf<String>()
    /** Live communication timeline; callbacks from parallel agent workers are serialized before UI updates. */
    private var collaborationListLayout: LinearLayout? = null
    private var collaborationScrollView: ScrollView? = null
    private var collaborationSummaryText: TextView? = null
    private var collaborationChatId: String = ""
    private val collaborationRenderedEntries = mutableListOf<String>()
    private val agentProviders = mutableMapOf<String, String>()
    private val agentConnection = mutableMapOf<String, String>()
    private val chatPrefs by lazy { getSharedPreferences("chat_state", Context.MODE_PRIVATE) }
    private var attachedText = ""
    private var attachedName = ""
    private var attachedMime = ""
    private var attachedBase64 = ""
    @Volatile private var currentChatId = ""
    private val chatTitles = linkedMapOf<String, String>()
    private val PICK_ATTACHMENT = 7103
    private val CREATE_BACKUP = 7101
    private val OPEN_BACKUP = 7102
    private val EXPORT_CHAT_FILE = 7104
    private val IMPORT_PROJECT_ARCHIVE = 7105
    private var pendingBackup = ""
    private var pendingExportText = ""
    private var isThinking = false
    private var thinkingChatId = ""
    private var thinkingLabel = "MASTER analizuje zadanie…"
    private var thinkingText: TextView? = null
    private var pulse: ObjectAnimator? = null
    @Volatile private var requestGen = 0
    @Volatile private var activeWorkerThread: Thread? = null
    @Volatile private var activeWorkerGeneration = -1
    @Volatile private var activeRequestScope: ProviderClient.RequestScope? = null
    private var draft = ""
    private var composerInput: EditText? = null

    // Panel boczny (lista rozmów)
    private var drawer: LinearLayout? = null
    private var scrim: View? = null
    private var drawerList: LinearLayout? = null
    private var drawerOpen = false
    private var drawerQuery = ""

    private data class ChatMeta(val id: String, val title: String, val updatedAt: Long, val count: Int)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)
        val recoveredTasks = runCatching { pl.aimasterpro.app.core.TaskCheckpointStore.recoverStaleRunning(this) }.getOrDefault(0)
        if (recoveredTasks > 0) android.util.Log.w("AIMasterRecovery", "Oznaczono $recoveredTasks przerwanych zadań jako INTERRUPTED.")
        seedDefaultProviders()
        selectedProviderId = chatPrefs.getString("selected_provider", "gemini") ?: "gemini"
        restoreMessages()
        loadAgentActivity()
        client = ProviderClient()
        AgentRole.values().forEach { role ->
            val defaultProvider = if (role == AgentRole.MASTER) selectedProviderId else "groq"
            agentProviders[role.name] = chatPrefs.getString("agent_provider_${role.name}", defaultProvider) ?: defaultProvider
            agentConnection[role.name] = "unknown"
        }
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.setBackgroundDrawable(ColorDrawable(bg))
        navigate("Czat")
        recoverPendingPatchReceiptAtStartup()
    }

    // =====================================================================
    //  Pomocnicze: wymiary, tła, ikony
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun ripple(color: Int, radiusDp: Int, strokeColor: Int = 0): RippleDrawable =
        RippleDrawable(
            ColorStateList.valueOf(Color.argb(40, 255, 255, 255)),
            rounded(color, radiusDp, strokeColor),
            rounded(Color.WHITE, radiusDp)
        )

    private fun icon(res: Int, sizeDp: Int = 24, tint: Int = white, padDp: Int = 10, click: (() -> Unit)? = null): ImageView {
        val v = ImageView(this)
        v.setImageResource(res)
        v.setColorFilter(tint)
        v.scaleType = ImageView.ScaleType.FIT_CENTER
        val p = dp(padDp)
        v.setPadding(p, p, p, p)
        val total = dp(sizeDp + padDp * 2)
        v.layoutParams = LinearLayout.LayoutParams(total, total)
        if (click != null) {
            v.background = ripple(Color.TRANSPARENT, (sizeDp + padDp * 2) / 2)
            v.setOnClickListener { click() }
        }
        return v
    }

    @Synchronized private fun navigate(label: String) {
        // Resolve role and conversation before building the top bar so its model/provider are correct.
        when (label) {
            "Czat" -> {
                selectedAgent = AgentRole.MASTER
                val lastId = chatPrefs.getString("last_user_chat_id", "") ?: ""
                currentChatId = if (lastId.isNotBlank()) lastId else UUID.randomUUID().toString()
                restoreMessagesForCurrentChat()
            }
            "Asystent" -> {
                val assistantId = assistantChatId()
                if (!isAssistantChatId(currentChatId) && currentChatId.isNotBlank()) {
                    chatPrefs.edit().putString("last_user_chat_id", currentChatId).apply()
                }
                selectedAgent = AgentRole.ASSISTANT
                currentChatId = assistantId
                restoreMessagesForCurrentChat()
            }
        }
        showShell(label)
        when (label) {
            "Czat", "Asystent" -> showChat()
            "Centrum" -> showCommandCenter()
            "Rozmowy" -> showAgentCollaborationScreen()
            "Monitor API" -> showApiMonitoringScreen()
            "Diagnostyka" -> showDiagnostics()
            "Modele" -> showProviders()
            "Agenci" -> showAgents()
            else -> showHelp()
        }
    }

    // =====================================================================
    //  Szkielet ekranu: górny pasek + zakładki + treść + panel boczny
    // =====================================================================

    private fun showShell(title: String) {
        if (title != "Rozmowy") {
            collaborationListLayout = null
            collaborationScrollView = null
            collaborationSummaryText = null
            collaborationChatId = ""
            collaborationRenderedEntries.clear()
        }
        screen = title
        pulse?.cancel(); pulse = null
        thinkingText = null
        composerInput = null

        val frame = FrameLayout(this)
        frame.setBackgroundColor(bg)
        root = frame

        val main = LinearLayout(this)
        main.orientation = LinearLayout.VERTICAL
        frame.addView(main, FrameLayout.LayoutParams(-1, -1))
        main.addView(buildTopBar(title))
        main.addView(buildTabs(title))

        content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        if (title == "Czat" || title == "Asystent") {
            main.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            val scroll = ScrollView(this)
            scroll.isFillViewport = true
            content.setPadding(dp(16), dp(14), dp(16), dp(24))
            scroll.addView(content)
            main.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        }

        val st = TextView(this)
        st.textSize = 12f
        st.setTextColor(cyan)
        st.gravity = Gravity.CENTER
        st.setPadding(dp(16), dp(4), dp(16), dp(4))
        st.visibility = View.GONE
        st.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { st.visibility = if (s.isNullOrBlank()) View.GONE else View.VISIBLE }
        })
        status = st
        if (title != "Czat" && title != "Asystent") main.addView(st)

        buildDrawer(frame)

        // Android 15+ wymusza tryb krawędź-do-krawędzi: ręcznie odsuwamy treść od pasków systemowych i klawiatury.
        frame.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 35) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            }
            insets
        }
        setContentView(frame)
        frame.requestApplyInsets()
    }

    private fun buildTopBar(title: String): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(4), dp(4), dp(4), dp(4))
        bar.setBackgroundColor(bg)
        bar.addView(icon(R.drawable.ic_menu) { openDrawer() })

        if (title == "Czat" || title == "Asystent") {
            val cfg = activeConfig()
            val ready = providerReady(cfg)
            val pick = LinearLayout(this)
            pick.orientation = LinearLayout.VERTICAL
            pick.setPadding(dp(10), dp(4), dp(10), dp(4))
            pick.background = ripple(Color.TRANSPARENT, 12)
            pick.setOnClickListener { showModelPicker() }

            val line = LinearLayout(this)
            line.orientation = LinearLayout.HORIZONTAL
            line.gravity = Gravity.CENTER_VERTICAL
            val name = TextView(this)
            name.text = selectedAgent.label
            name.textSize = 17f
            name.typeface = Typeface.DEFAULT_BOLD
            name.setTextColor(white)
            line.addView(name)
            val chevron = ImageView(this)
            chevron.setImageResource(R.drawable.ic_expand)
            chevron.setColorFilter(muted)
            line.addView(chevron, LinearLayout.LayoutParams(dp(18), dp(18)))
            pick.addView(line)

            val mode = currentWorkMode()
            val sub = TextView(this)
            sub.text = (if (ready) cfg!!.model else "Dodaj klucz API") + " • Tryb: ${mode.label}"
            sub.textSize = 12f
            sub.setTextColor(if (ready) muted else orange)
            sub.maxLines = 1
            sub.ellipsize = TextUtils.TruncateAt.END
            pick.addView(sub)

            bar.addView(pick, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(icon(R.drawable.ic_edit) { if (title == "Asystent") newAssistantChat() else newChat() })
        } else {
            val t = TextView(this)
            t.text = title
            t.textSize = 17f
            t.typeface = Typeface.DEFAULT_BOLD
            t.setTextColor(white)
            t.setPadding(dp(10), 0, 0, 0)
            bar.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        }
        bar.addView(icon(R.drawable.ic_more) { showChatMenu() })
        return bar
    }

    private fun buildTabs(active: String): View {
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(dp(6), 0, dp(6), 0)
        listOf("Centrum", "Czat", "Asystent", "Rozmowy", "Monitor API", "Diagnostyka", "Modele", "Agenci", "Pomoc").forEach { label ->
            val isActive = label == active
            val tab = LinearLayout(this)
            tab.orientation = LinearLayout.VERTICAL
            tab.setOnClickListener { navigate(label) }
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 14f
            tv.setTextColor(if (isActive) white else muted)
            if (isActive) tv.typeface = Typeface.DEFAULT_BOLD
            tv.setPadding(dp(14), dp(10), dp(14), dp(8))
            tab.addView(tv)
            val underline = View(this)
            underline.setBackgroundColor(if (isActive) orange else Color.TRANSPARENT)
            tab.addView(underline, LinearLayout.LayoutParams(-1, dp(2)))
            row.addView(tab)
        }
        hs.addView(row)
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        wrap.addView(hs)
        val line = View(this)
        line.setBackgroundColor(Color.rgb(48, 48, 48))
        wrap.addView(line, LinearLayout.LayoutParams(-1, 1))
        return wrap
    }

    private fun activeConfig(): ProviderConfig? =
        if (selectedAgent == AgentRole.MASTER) loadProvider(selectedProviderId)
        else loadProvider(agentProviders[selectedAgent.name] ?: selectedProviderId)

    private fun providerReady(config: ProviderConfig?): Boolean {
        if (config == null || config.model.isBlank() || config.baseUrl.isBlank()) return false
        val localNoAuth = config.id.equals("ollama", true) ||
            config.baseUrl.contains("127.0.0.1:11434") ||
            config.baseUrl.contains("localhost:11434") ||
            config.baseUrl.contains("10.0.2.2:11434")
        return localNoAuth || config.apiKey.isNotBlank()
    }

    private fun isModelPresentInCatalog(config: ProviderConfig): Boolean {
        if (config.model.isBlank()) return false
        if (config.parentProviderId.isNotBlank()) return true // explicitly created from the catalog
        return readModelCatalogEntries().any { it.providerId == config.id && it.info.id == config.model }
    }

    private fun currentWorkMode(): WorkMode =
        WorkMode.fromStored(chatPrefs.getString("work_mode_${currentChatId}", WorkMode.AUTOMATION.name))

    // =====================================================================
    //  Panel boczny z rozmowami (jak w ChatGPT)
    // =====================================================================

    private fun drawerRow(label: String, iconRes: Int, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(4), dp(2), dp(12), dp(2))
        row.background = ripple(Color.TRANSPARENT, 12)
        row.setOnClickListener { onClick() }
        row.addView(icon(iconRes, 22, white, 8))
        val t = TextView(this)
        t.text = label
        t.textSize = 15f
        t.setTextColor(white)
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun buildDrawer(frame: FrameLayout) {
        drawerOpen = false
        val w = minOf((resources.displayMetrics.widthPixels * 0.84f).toInt(), dp(330))

        val sc = View(this)
        sc.setBackgroundColor(Color.argb(150, 0, 0, 0))
        sc.alpha = 0f
        sc.visibility = View.GONE
        sc.setOnClickListener { closeDrawer() }
        frame.addView(sc, FrameLayout.LayoutParams(-1, -1))

        val d = LinearLayout(this)
        d.orientation = LinearLayout.VERTICAL
        d.setBackgroundColor(sidebarBg)
        d.setPadding(dp(12), dp(12), dp(12), dp(8))
        d.isClickable = true
        d.visibility = View.GONE
        d.translationX = -w.toFloat()
        frame.addView(d, FrameLayout.LayoutParams(w, -1))
        drawer = d
        scrim = sc

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val brand = TextView(this)
        brand.text = "AI Master Pro"
        brand.textSize = 19f
        brand.typeface = Typeface.DEFAULT_BOLD
        brand.setTextColor(white)
        brand.setPadding(dp(8), 0, 0, 0)
        head.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(icon(R.drawable.ic_close, 22, muted, 8) { closeDrawer() })
        d.addView(head)

        val newRow = drawerRow("Nowy czat", R.drawable.ic_edit) { closeDrawer(); newChat() }
        d.addView(newRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        val searchBox = LinearLayout(this)
        searchBox.orientation = LinearLayout.HORIZONTAL
        searchBox.gravity = Gravity.CENTER_VERTICAL
        searchBox.background = rounded(panel, 14)
        searchBox.setPadding(dp(4), 0, dp(10), 0)
        searchBox.addView(icon(R.drawable.ic_search, 18, muted, 8))
        val search = EditText(this)
        search.hint = "Szukaj w rozmowach"
        search.setHintTextColor(muted)
        search.setTextColor(white)
        search.textSize = 14f
        search.setSingleLine(true)
        search.background = null
        search.setPadding(0, dp(10), 0, dp(10))
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { drawerQuery = s?.toString().orEmpty(); populateDrawerList() }
        })
        searchBox.addView(search, LinearLayout.LayoutParams(0, -2, 1f))
        d.addView(searchBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })

        val listScroll = ScrollView(this)
        listScroll.isVerticalScrollBarEnabled = false
        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        listScroll.addView(list)
        d.addView(listScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        drawerList = list

        val divider = View(this)
        divider.setBackgroundColor(Color.rgb(48, 48, 48))
        d.addView(divider, LinearLayout.LayoutParams(-1, 1).apply { topMargin = dp(6); bottomMargin = dp(4) })
        d.addView(drawerRow("Biblioteka plików", R.drawable.ic_copy) { closeDrawer(); showLibrary() })
        d.addView(drawerRow("Modele i klucze API", R.drawable.ic_expand) { closeDrawer(); navigate("Modele") })
        d.addView(drawerRow("Zespół agentów", R.drawable.ic_menu) { closeDrawer(); navigate("Agenci") })
        val ver = TextView(this)
        ver.text = "Wersja 0.5.0"
        ver.textSize = 11f
        ver.setTextColor(muted)
        ver.setPadding(dp(12), dp(6), 0, dp(2))
        d.addView(ver)
    }

    private fun populateDrawerList() {
        val list = drawerList ?: return
        list.removeAllViews()
        val q = drawerQuery.trim().lowercase()
        val all = listChats().filter { q.isBlank() || it.title.lowercase().contains(q) }
        if (all.isEmpty()) {
            val empty = TextView(this)
            empty.text = if (q.isBlank()) "Brak zapisanych rozmów. Zacznij nowy czat." else "Nie znaleziono rozmów."
            empty.textSize = 13f
            empty.setTextColor(muted)
            empty.setPadding(dp(12), dp(12), dp(12), dp(12))
            list.addView(empty)
            return
        }
        val pinned = all.filter { isPinned(it.id) }
        val recent = all.filter { !isPinned(it.id) }
        fun section(titleText: String, items: List<ChatMeta>) {
            if (items.isEmpty()) return
            val h = TextView(this)
            h.text = titleText
            h.textSize = 12f
            h.typeface = Typeface.DEFAULT_BOLD
            h.setTextColor(muted)
            h.setPadding(dp(12), dp(14), dp(12), dp(4))
            list.addView(h)
            items.forEach { list.addView(chatRow(it)) }
        }
        section("Przypięte", pinned)
        section("Ostatnie", recent)
    }

    private fun chatRow(m: ChatMeta): View {
        val selected = m.id == currentChatId
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), 0, dp(2), 0)
        row.background = ripple(if (selected) panel else Color.TRANSPARENT, 12)
        row.setOnClickListener { closeDrawer(); openChat(m.id) }
        row.setOnLongClickListener { showChatOptions(m.id); true }
        val t = TextView(this)
        t.text = m.title
        t.textSize = 15f
        t.setTextColor(white)
        t.maxLines = 1
        t.ellipsize = TextUtils.TruncateAt.END
        t.setPadding(0, dp(12), 0, dp(12))
        row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        if (isPinned(m.id)) {
            val star = TextView(this)
            star.text = "★"
            star.textSize = 12f
            star.setTextColor(orange)
            star.setPadding(dp(4), 0, dp(2), 0)
            row.addView(star)
        }
        row.addView(icon(R.drawable.ic_more, 18, muted, 8) { showChatOptions(m.id) })
        return row
    }

    private fun openDrawer() {
        val d = drawer ?: return
        val s = scrim ?: return
        hideKeyboard(d)
        populateDrawerList()
        d.visibility = View.VISIBLE
        s.visibility = View.VISIBLE
        d.animate().translationX(0f).setDuration(220).start()
        s.animate().alpha(1f).setDuration(220).start()
        drawerOpen = true
    }

    private fun closeDrawer() {
        val d = drawer ?: return
        val s = scrim ?: return
        if (!drawerOpen) return
        drawerOpen = false
        hideKeyboard(d)
        d.animate().translationX(-d.width.toFloat()).setDuration(200).withEndAction { d.visibility = View.GONE }.start()
        s.animate().alpha(0f).setDuration(200).withEndAction { s.visibility = View.GONE }.start()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        when {
            drawerOpen -> closeDrawer()
            screen != "Czat" -> navigate("Czat")
            else -> super.onBackPressed()
        }
    }

    // =====================================================================
    //  Ekrany pomocnicze: karty, przyciski, pola (wspólny styl)
    // =====================================================================

    private fun card(title: String, description: String = ""): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(16), dp(14), dp(16), dp(14))
        box.background = rounded(cardBg, 16, border)
        val tt = TextView(this)
        tt.text = title
        tt.textSize = 16f
        tt.typeface = Typeface.DEFAULT_BOLD
        tt.setTextColor(white)
        box.addView(tt)
        if (description.isNotBlank()) {
            val dd = TextView(this)
            dd.text = description
            dd.textSize = 13f
            dd.setTextColor(muted)
            dd.setLineSpacing(dp(2).toFloat(), 1f)
            dd.setPadding(0, dp(5), 0, 0)
            box.addView(dd)
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.bottomMargin = dp(12)
        content.addView(box, lp)
        return box
    }

    private fun action(label: String, parent: LinearLayout = content, onClick: () -> Unit) {
        val b = TextView(this)
        b.text = label
        b.textSize = 14f
        b.setTextColor(white)
        b.gravity = Gravity.CENTER
        b.setPadding(dp(14), dp(12), dp(14), dp(12))
        b.background = ripple(panel, 12)
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(8)
        parent.addView(b, lp)
    }

    private fun field(hint: String, value: String = "", inputType: Int = InputType.TYPE_CLASS_TEXT): EditText {
        val e = EditText(this)
        e.hint = hint
        e.setText(value)
        e.setTextColor(white)
        e.setHintTextColor(muted)
        e.inputType = inputType
        e.setSingleLine(true)
        e.setPadding(dp(12), dp(10), dp(12), dp(10))
        e.background = rounded(panel, 10)
        return e
    }

    // =====================================================================
    //  Ekran czatu
    // =====================================================================

    private fun showChat() {
        val chatDestination = pl.aimasterpro.app.core.ChatScreenPolicy.destination(screen)
        loadAgentActivity()
        val screenW = resources.displayMetrics.widthPixels
        val side = maxOf(dp(16), (screenW - dp(760)) / 2)
        val textW = minOf(screenW, dp(760)) - dp(32)

        val area = FrameLayout(this)
        val chatScroll = ScrollView(this)
        chatScroll.isFillViewport = true
        chatScroll.isVerticalScrollBarEnabled = false
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(side, dp(8), side, dp(16))
        chatScroll.addView(col)
        area.addView(chatScroll, FrameLayout.LayoutParams(-1, -1))
        content.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))

        val chatActivityRows = agentActivitySnapshot()
        if (chatActivityRows.isNotEmpty()) {
            val collabChip = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(cardBg, 16, border)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { showAgentCollaborationViewer() }
            }
            collabChip.addView(icon(R.drawable.ic_more, 16, cyan, 4))
            val collabTv = TextView(this).apply {
                text = "💬 Zobacz historię wymiany danych między agentami (${chatActivityRows.size})"
                textSize = 13f
                setTextColor(cyan)
                setPadding(dp(6), 0, 0, 0)
            }
            collabChip.addView(collabTv)
            collabChip.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(12); gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(collabChip)
        }

        if (messages.isEmpty()) {
            col.gravity = Gravity.CENTER
            val h = TextView(this)
            h.text = "W czym mogę Ci pomóc?"
            h.textSize = 26f
            h.typeface = Typeface.DEFAULT_BOLD
            h.setTextColor(white)
            h.gravity = Gravity.CENTER
            col.addView(h)
            val sub = TextView(this)
            sub.text = "Pisz, dodawaj pliki i obrazy, wracaj do wcześniejszych rozmów z panelu po lewej."
            sub.textSize = 14f
            sub.setTextColor(muted)
            sub.gravity = Gravity.CENTER
            sub.setPadding(dp(8), dp(8), dp(8), dp(20))
            col.addView(sub)
            listOf("Wyjaśnij błąd w kodzie", "Pomóż zaplanować projekt", "Porównaj modele AI").forEach { example ->
                val chip = TextView(this)
                chip.text = example
                chip.textSize = 14f
                chip.setTextColor(white)
                chip.gravity = Gravity.CENTER
                chip.setPadding(dp(16), dp(12), dp(16), dp(12))
                chip.background = ripple(Color.TRANSPARENT, 20, border)
                chip.setOnClickListener {
                    draft = example
                    composerInput?.setText(example)
                    composerInput?.setSelection(example.length)
                    composerInput?.requestFocus()
                }
                col.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
            }
        } else {
            // Pair each assistant answer with its own immutable request ID; never rate only the last reply.
            val chatEventsById = pl.aimasterpro.app.core.LearningJournalStore.load(this)
                .filter { it.kind == "chat" && it.taskId.startsWith("chat-$currentChatId-") }
                .associateBy { it.taskId }
            var assistantIndex = 0
            messages.forEach { (role, text) ->
                addMessage(col, role, text, textW)
                if (role == "assistant") {
                    val taskId = assistantTaskIds.getOrNull(assistantIndex++)
                    val event = taskId?.let { chatEventsById[it] }
                    if (event != null && event.status.equals("COMPLETED", true)) addChatAnswerFeedback(col, event, chatDestination)
                }
            }
        }

        if (isThinking && thinkingChatId == currentChatId) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, dp(14), 0, dp(14))
            val dot = View(this)
            dot.background = rounded(white, 6)
            row.addView(dot, LinearLayout.LayoutParams(dp(12), dp(12)))
            val anim = ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.25f)
            anim.duration = 700
            anim.repeatCount = ValueAnimator.INFINITE
            anim.repeatMode = ValueAnimator.REVERSE
            anim.start()
            pulse = anim
            val label = TextView(this)
            label.text = thinkingLabel
            label.textSize = 13f
            label.setTextColor(muted)
            label.setPadding(dp(10), 0, 0, 0)
            thinkingText = label
            row.addView(label)
            col.addView(row)
        }

        // Przycisk „na dół", gdy użytkownik przewinął w górę
        val down = ImageView(this)
        down.setImageResource(R.drawable.ic_arrow_down)
        down.setColorFilter(white)
        down.setPadding(dp(8), dp(8), dp(8), dp(8))
        down.background = rounded(panel, 20, border)
        down.elevation = dp(4).toFloat()
        down.visibility = View.GONE
        down.setOnClickListener { chatScroll.smoothScrollTo(0, col.height) }
        area.addView(down, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(10) })
        chatScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            val remaining = col.height - (scrollY + chatScroll.height)
            down.visibility = if (remaining > dp(160)) View.VISIBLE else View.GONE
        }
        chatScroll.post { chatScroll.scrollTo(0, maxOf(0, col.height - chatScroll.height)) }

        // ---- Pole wpisywania ----
        val composer = LinearLayout(this)
        composer.orientation = LinearLayout.VERTICAL
        composer.setPadding(side, dp(4), side, dp(10))
        (status.parent as? ViewGroup)?.removeView(status)
        composer.addView(status)

        if (attachedName.isNotBlank()) {
            val chipRow = LinearLayout(this)
            chipRow.orientation = LinearLayout.HORIZONTAL
            chipRow.gravity = Gravity.CENTER_VERTICAL
            chipRow.background = rounded(cardBg, 12, border)
            chipRow.setPadding(dp(12), dp(2), dp(2), dp(2))
            val nameTv = TextView(this)
            nameTv.text = "📎 $attachedName"
            nameTv.textSize = 13f
            nameTv.setTextColor(cyan)
            nameTv.maxLines = 1
            nameTv.ellipsize = TextUtils.TruncateAt.MIDDLE
            chipRow.addView(nameTv, LinearLayout.LayoutParams(0, -2, 1f))
            chipRow.addView(icon(R.drawable.ic_close, 16, white, 8) {
                attachedName = ""; attachedText = ""; attachedMime = ""; attachedBase64 = ""
                navigate(chatDestination)
            })
            composer.addView(chipRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        }

        val pill = LinearLayout(this)
        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.BOTTOM
        pill.background = rounded(panel, 26)
        pill.setPadding(dp(6), dp(6), dp(6), dp(6))
        pill.addView(icon(R.drawable.ic_add, 22, white, 8) { showAttachmentOptions() })

        val input = EditText(this)
        input.hint = "Napisz wiadomość…"
        input.setHintTextColor(muted)
        input.setTextColor(white)
        input.textSize = 16f
        input.background = null
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
        input.minLines = 1
        input.maxLines = 6
        input.isVerticalScrollBarEnabled = true
        input.setHorizontallyScrolling(false)
        input.setPadding(dp(8), dp(10), dp(8), dp(10))
        input.gravity = Gravity.CENTER_VERTICAL
        input.setText(draft)
        input.setSelection(input.text.length)
        composerInput = input
        pill.addView(input, LinearLayout.LayoutParams(0, -2, 1f))

        val send = ImageView(this)
        send.scaleType = ImageView.ScaleType.CENTER_INSIDE
        send.setPadding(dp(8), dp(8), dp(8), dp(8))
        pill.addView(send, LinearLayout.LayoutParams(dp(38), dp(38)))

        fun refreshSend() {
            val busy = isThinking && thinkingChatId == currentChatId
            val has = input.text.isNotBlank() || attachedName.isNotBlank()
            if (busy) {
                send.setImageResource(R.drawable.ic_stop)
                send.setColorFilter(Color.BLACK)
                send.background = rounded(white, 19)
            } else {
                send.setImageResource(R.drawable.ic_arrow_up)
                send.setColorFilter(if (has) Color.BLACK else Color.rgb(120, 120, 120))
                send.background = rounded(if (has) white else Color.rgb(70, 70, 70), 19)
            }
        }
        refreshSend()
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { draft = s?.toString().orEmpty(); refreshSend() }
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                if (input.text.isNotBlank() || attachedName.isNotBlank()) sendChatMessage(input)
                true
            } else false
        }
        send.setOnClickListener {
            if (isThinking && thinkingChatId == currentChatId) stopGeneration() else sendChatMessage(input)
        }
        composer.addView(pill, LinearLayout.LayoutParams(-1, -2))
        content.addView(composer, LinearLayout.LayoutParams(-1, -2))
    }

    private fun stopGeneration() {
        // Stop is a real cancellation: interrupt the orchestrator and disconnect active HTTP calls.
        val stopScreen = if (screen == "Asystent") "Asystent" else "Czat"
        requestGen++
        activeWorkerGeneration = -1
        activeRequestScope?.let { scope -> client.cancelRequestScope(scope) }
        activeRequestScope = null
        activeWorkerThread?.interrupt()
        activeWorkerThread = null
        isThinking = false
        thinkingChatId = ""
        thinkingText = null
        pulse?.cancel()
        pulse = null
        showShell(stopScreen)
        showChat()
        toast("Przerwano generowanie. Aktywne żądania HTTP zostały anulowane.")
    }

    // =====================================================================
    //  Wiadomości: dymek użytkownika, odpowiedź AI bez dymka, kopiowanie
    // =====================================================================

    private fun addChatAnswerFeedback(parent: LinearLayout, event: pl.aimasterpro.app.core.LearningJournalStore.Event, destination: String) {
        val currentRating = pl.aimasterpro.app.core.QualityLearningStore.feedback(this)
            .firstOrNull { it.targetId == event.taskId && it.targetType == "CHAT_ANSWER" }?.rating
        val meta = TextView(this)
        val elapsed = if (event.durationMs > 0) " • ${"%.1f".format(java.util.Locale.US, event.durationMs / 1000.0)} s" else ""
        val modelLabel = event.model.ifBlank { "model nieznany" }
        val roleLabel = AgentRole.values().firstOrNull { it.name == event.agentId }?.label ?: event.agentId.ifBlank { "MASTER" }
        val providerLabel = event.providerId.ifBlank { "provider?" }
        meta.text = "$roleLabel • $providerLabel / $modelLabel$elapsed"
        meta.textSize = 11f
        meta.setTextColor(muted)
        meta.setPadding(dp(2), dp(2), 0, dp(2))
        parent.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
            setPadding(0, dp(2), 0, dp(8))
        }
        fun addRating(label: String, rating: Int) {
            val selected = currentRating == rating
            val button = TextView(this).apply {
                text = (if (selected) "✓ " else "") + label
                textSize = 12f
                setTextColor(if (selected) cyan else muted)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                background = ripple(cardBg, 16, if (selected) cyan else border)
                contentDescription = if (rating > 0) "Oceń odpowiedź pozytywnie" else "Oceń odpowiedź negatywnie"
                setOnClickListener {
                    pl.aimasterpro.app.core.QualityLearningStore.rate(this@MainActivity, event.taskId,
                        "CHAT_ANSWER", rating, "", event.agentId, event.providerId, event.model, event.category)
                    toast(if (rating > 0) "Zapisano pozytywną ocenę tej odpowiedzi." else "Zapisano negatywną ocenę tej odpowiedzi.")
                    navigate(destination)
                }
            }
            row.addView(button, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) })
        }
        addRating("👍 Pomogło", 1)
        addRating("👎 Nie pomogło", -1)
        parent.addView(row)
    }

    private fun messageActions(text: String, alignEnd: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = if (alignEnd) Gravity.END else Gravity.START
        row.addView(icon(R.drawable.ic_copy, 16, muted, 8) { copyText(text) })
        return row
    }

    private fun addMessage(parent: LinearLayout, role: String, bodyText: String, textW: Int) {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        if (role == "user") {
            val bubble = TextView(this)
            bubble.setTextIsSelectable(true)
            bubble.autoLinkMask = Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES
            bubble.text = bodyText
            bubble.movementMethod = LinkMovementMethod.getInstance()
            bubble.setLinkTextColor(cyan)
            bubble.textSize = 16f
            bubble.setTextColor(white)
            bubble.setLineSpacing(dp(2).toFloat(), 1f)
            bubble.maxWidth = (textW * 0.85f).toInt()
            bubble.background = rounded(panel, 20)
            bubble.setPadding(dp(16), dp(10), dp(16), dp(10))
            wrap.addView(bubble, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            wrap.addView(messageActions(bodyText, true), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            parent.addView(wrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        } else {
            renderMarkdown(wrap, bodyText)
            wrap.addView(messageActions(bodyText, false))
            parent.addView(wrap, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10); bottomMargin = dp(6) })
        }
    }

    // =====================================================================
    //  Prosty renderer Markdown: nagłówki, listy, **pogrubienie**, `kod`, bloki kodu, tabele
    // =====================================================================

    private fun renderMarkdown(parent: LinearLayout, text: String) {
        val lines = text.replace("\r\n", "\n").split("\n")
        val buf = mutableListOf<String>()
        fun flushText() {
            if (buf.isNotEmpty()) { addRichText(parent, buf.toList()); buf.clear() }
        }
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trimStart()
            if (trimmed.startsWith("```")) {
                flushText()
                val lang = trimmed.removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { codeLines.add(lines[i]); i++ }
                i++ // pomiń zamykający znacznik
                addCodeBlock(parent, lang, codeLines.joinToString("\n"))
            } else if (trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparator(lines[i + 1])) {
                flushText()
                val rows = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows.add(lines[i]); i++ }
                addTable(parent, rows)
            } else {
                buf.add(lines[i]); i++
            }
        }
        flushText()
    }

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        return t.startsWith("|") && t.contains("-") && t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun addRichText(parent: LinearLayout, lines: List<String>) {
        var start = 0
        var end = lines.size
        while (start < end && lines[start].isBlank()) start++
        while (end > start && lines[end - 1].isBlank()) end--
        if (start >= end) return

        val headingRe = Regex("^(#{1,4})\\s+(.*)$")
        val bulletRe = Regex("^(\\s*)[-*•]\\s+(.*)$")
        val numberRe = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
        val quoteRe = Regex("^>\\s?(.*)$")

        val sb = SpannableStringBuilder()
        var first = true
        var pendingBlank = false
        for (idx in start until end) {
            val raw = lines[idx]
            if (raw.isBlank()) { pendingBlank = true; continue }
            if (!first) sb.append("\n")
            if (pendingBlank && !first) sb.append("\n")
            pendingBlank = false
            first = false

            val lineStart = sb.length
            val heading = headingRe.find(raw)
            val bullet = bulletRe.find(raw)
            val number = numberRe.find(raw)
            val quote = quoteRe.find(raw)
            when {
                heading != null -> {
                    val level = heading.groupValues[1].length
                    appendInline(sb, heading.groupValues[2])
                    val scale = when (level) { 1 -> 1.35f; 2 -> 1.22f; 3 -> 1.1f; else -> 1.05f }
                    sb.setSpan(RelativeSizeSpan(scale), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                bullet != null -> {
                    val indent = "    ".repeat(bullet.groupValues[1].length / 2)
                    sb.append(indent).append("•  ")
                    appendInline(sb, bullet.groupValues[2])
                }
                number != null -> {
                    val indent = "    ".repeat(number.groupValues[1].length / 2)
                    sb.append(indent).append(number.groupValues[2]).append(".  ")
                    appendInline(sb, number.groupValues[3])
                }
                quote != null -> {
                    sb.append("▎ ")
                    appendInline(sb, quote.groupValues[1])
                    sb.setSpan(ForegroundColorSpan(muted), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                raw.trim().length >= 3 && raw.trim().all { it == '-' || it == '_' || it == '*' } -> {
                    sb.append("────────────")
                    sb.setSpan(ForegroundColorSpan(border), lineStart, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                else -> appendInline(sb, raw)
            }
        }
        val tv = TextView(this)
        tv.setTextIsSelectable(true)
        tv.autoLinkMask = Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES
        tv.text = sb
        tv.movementMethod = LinkMovementMethod.getInstance()
        tv.setLinkTextColor(cyan)
        tv.textSize = 16f
        tv.setTextColor(white)
        tv.setLineSpacing(dp(3).toFloat(), 1f)
        parent.addView(tv, LinearLayout.LayoutParams(-1, -2))
    }

    /** Obsługuje **pogrubienie**, *kursywę* i `kod` w jednej linii. */
    private fun appendInline(sb: SpannableStringBuilder, s: String) {
        var i = 0
        while (i < s.length) {
            val link = if (s[i] == '[') markdownLinkRegex.matchAt(s, i) else null
            if (link != null) {
                val label = link.groupValues[1]
                val url = link.groupValues[2]
                val start = sb.length
                appendInline(sb, label)
                if (sb.length > start) {
                    sb.setSpan(URLSpan(url), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(cyan), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                i += link.value.length
                continue
            }
            if (s.startsWith("**", i)) {
                val j = s.indexOf("**", i + 2)
                if (j > i + 2) {
                    val st = sb.length
                    appendInline(sb, s.substring(i + 2, j))
                    sb.setSpan(StyleSpan(Typeface.BOLD), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = j + 2
                    continue
                }
            }
            if (s[i] == '`') {
                val j = s.indexOf('`', i + 1)
                if (j > i + 1) {
                    val st = sb.length
                    sb.append(s.substring(i + 1, j))
                    sb.setSpan(TypefaceSpan("monospace"), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(Color.rgb(64, 64, 64)), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = j + 1
                    continue
                }
            }
            if (s[i] == '*' && i + 1 < s.length && s[i + 1] != ' ' && s[i + 1] != '*') {
                val j = s.indexOf('*', i + 1)
                if (j > i + 1 && s[j - 1] != ' ') {
                    val st = sb.length
                    appendInline(sb, s.substring(i + 1, j))
                    sb.setSpan(StyleSpan(Typeface.ITALIC), st, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    i = j + 1
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
    }

    private fun addCodeBlock(parent: LinearLayout, lang: String, code: String) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = rounded(codeBg, 12, Color.rgb(55, 55, 55))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(14), dp(2), dp(6), dp(2))
        val r = dp(12).toFloat()
        val headBg = GradientDrawable()
        headBg.setColor(Color.rgb(45, 45, 45))
        headBg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        head.background = headBg

        val langTv = TextView(this)
        langTv.text = if (lang.isBlank()) "kod" else lang
        langTv.textSize = 12f
        langTv.setTextColor(muted)
        head.addView(langTv, LinearLayout.LayoutParams(0, -2, 1f))

        val copyLabel = TextView(this)
        copyLabel.text = "Kopiuj"
        copyLabel.textSize = 12f
        copyLabel.setTextColor(muted)
        val copyRow = LinearLayout(this)
        copyRow.orientation = LinearLayout.HORIZONTAL
        copyRow.gravity = Gravity.CENTER_VERTICAL
        copyRow.background = ripple(Color.TRANSPARENT, 8)
        copyRow.setPadding(dp(8), dp(8), dp(8), dp(8))
        val ci = ImageView(this)
        ci.setImageResource(R.drawable.ic_copy)
        ci.setColorFilter(muted)
        copyRow.addView(ci, LinearLayout.LayoutParams(dp(14), dp(14)))
        copyLabel.setPadding(dp(6), 0, 0, 0)
        copyRow.addView(copyLabel)
        copyRow.setOnClickListener {
            copyText(code, silent = true)
            copyLabel.text = "Skopiowano"
            copyLabel.postDelayed({ copyLabel.text = "Kopiuj" }, 1500)
        }
        head.addView(copyRow)
        box.addView(head, LinearLayout.LayoutParams(-1, -2))

        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        val codeTv = TextView(this)
        codeTv.text = code
        codeTv.typeface = Typeface.MONOSPACE
        codeTv.textSize = 13f
        codeTv.setTextColor(Color.rgb(230, 230, 230))
        codeTv.setTextIsSelectable(true)
        codeTv.setPadding(dp(14), dp(10), dp(14), dp(12))
        hs.addView(codeTv)
        box.addView(hs, LinearLayout.LayoutParams(-1, -2))
        parent.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
    }

    private fun addTable(parent: LinearLayout, rows: List<String>) {
        val parsed = rows.filter { !isTableSeparator(it) }.map { row ->
            row.trim().trim('|').split("|").map { it.trim().replace("**", "").replace("`", "") }
        }
        if (parsed.isEmpty()) return
        val cols = parsed.maxOf { it.size }
        val widths = IntArray(cols) { c -> parsed.maxOf { r -> if (c < r.size) r[c].length else 0 } }
        val sb = StringBuilder()
        parsed.forEachIndexed { idx, r ->
            sb.append((0 until cols).joinToString("  │  ") { c -> (if (c < r.size) r[c] else "").padEnd(widths[c]) })
            if (idx == 0) sb.append("\n").append((0 until cols).joinToString("──┼──") { c -> "─".repeat(widths[c]) })
            if (idx < parsed.size - 1) sb.append("\n")
        }
        val tv = TextView(this)
        tv.text = sb.toString()
        tv.typeface = Typeface.MONOSPACE
        tv.textSize = 13f
        tv.setTextColor(white)
        tv.setTextIsSelectable(true)
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        val hs = HorizontalScrollView(this)
        hs.isHorizontalScrollBarEnabled = false
        hs.background = rounded(cardBg, 12, border)
        hs.addView(tv)
        parent.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8); bottomMargin = dp(8) })
    }

    // =====================================================================
    //  Wysyłanie wiadomości
    // =====================================================================

    private fun isVerifiedFreeConfig(config: ProviderConfig): Boolean {
        if (config.id.equals("ollama", true) || config.baseUrl.contains("11434") || config.baseUrl.contains("localhost") || config.baseUrl.contains("127.0.0.1")) return true
        val baseId = config.parentProviderId.ifBlank { config.id }
        val entry = readModelCatalogEntries().firstOrNull { it.providerId == baseId && it.info.id == config.model }
        val pricingFree = entry?.info?.freeByPricing
        if (pricingFree != null) return pricingFree
        return pl.aimasterpro.app.core.FreeModelsCatalog.isFreeModel(baseId, config.model, config.baseUrl)
    }

    private fun chatWithFallback(config: ProviderConfig, systemPrompt: String, userText: String, mime: String, b64: String,
                                 requestTaskId: String, requestAgentId: String, requestChatId: String,
                                 requestScope: ProviderClient.RequestScope, budgetReservationId: String = ""): ChatResponse {
        val allConfigs = store.allConfigs().mapNotNull { j -> runCatching { loadProvider(j.optString("id")) }.getOrNull() }
        val primaryBaseId = config.parentProviderId.ifBlank { config.id }
        val freeBackups = allConfigs.filter {
            it.id != config.id && providerReady(it) && isVerifiedFreeConfig(it) &&
                !(it.model == config.model && it.parentProviderId.ifBlank { it.id } == primaryBaseId)
        }.distinctBy { "${it.parentProviderId.ifBlank { it.id }}:${it.model}" }
        val primaryIsFree = isVerifiedFreeConfig(config)
        val ordered = pl.aimasterpro.app.core.ModelHealthTracker.rank(
            this, (if (primaryIsFree) listOf(config) else emptyList()) + freeBackups
        )
        if (!primaryIsFree) {
            logAgentForChat(requestChatId, "⛔ Zabezpieczenie kosztów: profil ${config.name} / ${config.model} nie ma potwierdzonego darmowego statusu. Pomijam go i sprawdzam wyłącznie zweryfikowane darmowe profile.")
        }
        if (ordered.isEmpty()) {
            throw IllegalStateException("Brak skonfigurowanego, zweryfikowanego darmowego modelu. Nie wysłano żądania do płatnego lub niezweryfikowanego API.")
        }
        var ownedBudgetReservationId = ""
        var effectiveBudgetReservationId = budgetReservationId
        if (budgetReservationId.isBlank()) {
            val estimatedInput = (systemPrompt.length.toLong() + userText.length.toLong() + 2L) / 3L
            val directBudget = pl.aimasterpro.app.core.ApiTokenTracker.planBatch(
                this,
                listOf(pl.aimasterpro.app.core.BudgetRequest(
                    taskId = requestTaskId,
                    agentId = requestAgentId,
                    providerId = ordered.first().id,
                    baseUrl = ordered.first().baseUrl,
                    modelName = ordered.first().model,
                    estimatedInputTokens = estimatedInput,
                    estimatedOutputTokens = 4_096L
                ))
            )
            if (requestTaskId !in directBudget.allowedTaskIds) {
                pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(directBudget.reservationId)
                val reason = directBudget.blockedReasons[requestTaskId] ?: "Nie udało się zarezerwować budżetu dla pojedynczego wywołania."
                throw IllegalStateException("Smart Budget wstrzymał wywołanie: ${reason}")
            }
            ownedBudgetReservationId = directBudget.reservationId
            effectiveBudgetReservationId = directBudget.reservationId
            runCatching { logAgentForChat(requestChatId, "🧠 Smart Budget: zarezerwowano ${directBudget.estimatedReservedTokens} tokenów dla pojedynczego wywołania.") }
        }

        var lastErr: Throwable? = null
        try {
            for (cfg in ordered) {
                if (Thread.currentThread().isInterrupted) throw ProviderException(null, "Żądanie anulowane przez użytkownika.", category = "CANCELLED")
                if (!providerReady(cfg)) continue
                val quotaKey = pl.aimasterpro.app.core.ProviderQuotaScheduler.key(cfg.parentProviderId.ifBlank { cfg.id }, cfg.baseUrl, cfg.model)
                val persistedWait = runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.remainingMs(this, cfg) }.getOrDefault(0L)
                if (persistedWait > 0L) {
                    val note = "${cfg.name}/${cfg.model}: zapisany cooldown po HTTP 429 (${persistedWait / 1000}s)."
                    logAgentForChat(requestChatId, "⏸️ Smart Scheduler: $note Pomijam profil bez wysyłania żądania.")
                    lastErr = IllegalStateException(note)
                    continue
                }
                val gate = pl.aimasterpro.app.core.ProviderQuotaScheduler.acquire(quotaKey, isVerifiedFreeConfig(cfg))
                val lease = gate.lease
                if (lease == null) {
                    if (gate.reason == "INTERRUPTED" || Thread.currentThread().isInterrupted) {
                        throw ProviderException(null, "Przerwano oczekiwanie na dostępność modelu.", category = "CANCELLED")
                    }
                    // Scheduler contention/cooldown is local coordination, not provider health evidence.
                    val note = "${cfg.name}/${cfg.model}: model chwilowo zajęty lub w cooldown (${gate.reason ?: "LIMIT"}, ${gate.waitMs} ms)."
                    logAgentForChat(requestChatId, "⏳ Smart Scheduler: $note Przechodzę do kolejnego skonfigurowanego modelu.")
                    lastErr = IllegalStateException(note)
                    continue
                }
                var modelCallStartedAt = 0L
                try {
                    modelCallStartedAt = System.currentTimeMillis()
                    val res = client.chat(cfg, systemPrompt, userText, mime, b64, requestScope)
                    // A successful provider response may already be billable. Local telemetry or
                    // persistence failures must not be treated as provider failures or trigger fallback.
                    runCatching {
                        pl.aimasterpro.app.core.ModelHealthTracker.record(this, cfg, true, "", System.currentTimeMillis() - modelCallStartedAt)
                    }.onFailure { error ->
                        runCatching { logAgentForChat(requestChatId, "[HEALTH RECORD WARNING] ${error.message ?: error.javaClass.simpleName}") }
                    }
                    runCatching { pl.aimasterpro.app.core.ProviderQuotaScheduler.markSuccess(quotaKey) }
                    runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.markSuccess(this, cfg) }
                    runCatching {
                        TokenTracker.recordUsage(this, cfg.id, systemPrompt + "\n" + userText, res.text, "OK", "Aktywny / Działa")
                    }.onFailure { error ->
                        runCatching { logAgentForChat(requestChatId, "[LOCAL USAGE WARNING] Nie zapisano lokalnej historii wywołania: ${error.message ?: error.javaClass.simpleName}") }
                    }
                    runCatching {
                        pl.aimasterpro.app.core.ApiTokenTracker.recordQuery(
                            this, cfg.id, cfg.model, requestAgentId, requestTaskId,
                            res.usage.inputTokens, res.usage.outputTokens, res.usage.reasoningTokens,
                            res.usage.cachedTokens, res.usage.status, cfg.baseUrl, effectiveBudgetReservationId
                        )
                    }.onFailure { error ->
                        runCatching { logAgentForChat(requestChatId, "[USAGE_RECORD_FAILED] Odpowiedź API mogła zostać rozliczona, ale zapis tokenów nie powiódł się: ${error.message ?: error.javaClass.simpleName}") }
                    }
                    if (Thread.currentThread().isInterrupted) {
                        throw ProviderException(null, "Odpowiedź odebrano po anulowaniu; wykorzystanie API zostało rozliczone.", category = "CANCELLED")
                    }
                    if (cfg.id != config.id) {
                        runCatching {
                            logAgentForChat(requestChatId, "🧭 Smart Routing: wybrano profil ${cfg.name} (${cfg.model}) zamiast pierwotnego ${config.name} na podstawie dostępności i historii kondycji.")
                        }
                        runCatching {
                            logAgentDialogueForChat(requestChatId, "SYSTEM", "MASTER", "Routing wybrał profil ${cfg.name} na podstawie dostępności, limitów i lokalnej historii kondycji modeli.", "SMART_ROUTING")
                        }
                        runCatching {
                            runOnUiThread {
                                status.text = "🔄 Failover: Przełączono na ${cfg.name}"
                                status.setTextColor(cyan)
                            }
                        }
                    }
                    return res.copy(providerId = cfg.id, model = cfg.model)
                } catch (t: Throwable) {
                    if ((t as? ProviderException)?.category == "CANCELLED" || Thread.currentThread().isInterrupted) throw t
                    lastErr = t
                    runCatching {
                        pl.aimasterpro.app.core.ModelHealthTracker.record(
                            this, cfg, false, if ((t as? ProviderException)?.httpCode == 429) "HTTP_429" else (t as? ProviderException)?.category ?: "UNKNOWN",
                            if (modelCallStartedAt > 0L) System.currentTimeMillis() - modelCallStartedAt else 0L
                        )
                    }.onFailure { error ->
                        runCatching { logAgentForChat(requestChatId, "[HEALTH RECORD WARNING] Nie udało się zapisać błędu providera: ${error.message ?: error.javaClass.simpleName}") }
                    }
                    if ((t as? ProviderException)?.httpCode == 429) {
                        val processCooldown = pl.aimasterpro.app.core.ProviderQuotaScheduler.markRateLimited(quotaKey)
                        val persistedCooldown = runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.markRateLimited(this, cfg) }.getOrDefault(0L)
                        val cooldown = maxOf(processCooldown, persistedCooldown)
                        logAgentForChat(requestChatId, "⏸️ Smart Scheduler: ${cfg.name}/${cfg.model} otrzymał HTTP 429. Wstrzymuję ten model na ${cooldown / 1000}s; blokada przetrwa restart aplikacji.")
                    }
                    val msg = (t.message.orEmpty() + " " + (t as? ProviderException)?.responseBody.orEmpty()).lowercase()
                    val httpCode = (t as? ProviderException)?.httpCode
                    val isAuthOrClientError = httpCode != null && httpCode in 400..499 && httpCode != 429
                    val isTokenOrRateLimit = httpCode == 429 || msg.contains("429") || msg.contains("quota") || msg.contains("limit") || msg.contains("token") || msg.contains("exhausted") || msg.contains("overloaded") || msg.contains("rate")

                    if (isAuthOrClientError) {
                        logAgentForChat(requestChatId, "❌ Błąd autoryzacji/konfiguracji dla ${cfg.name} (HTTP $httpCode): ${t.message}. Nie wykonuję failoveru dla błędu klienta.")
                        logAgentDialogueForChat(requestChatId, cfg.name, "SYSTEM", "Błąd klucza/konfiguracji (HTTP $httpCode): ${t.message}. Sprawdź ten profil; nie zmieniam dostawcy automatycznie.", "CONFIG_ERROR")
                        throw t
                    } else if (cfg != ordered.last()) {
                        if (isTokenOrRateLimit) {
                            TokenTracker.recordUsage(this, cfg.id, systemPrompt + "\n" + userText, "", "RATE_LIMITED_429", "Limit wyczerpany • 429 / Quota")
                            logAgentForChat(requestChatId, "⚠️ ${cfg.name}: limit/quota (HTTP ${httpCode ?: "brak"}). Sprawdzam wyłącznie potwierdzony darmowy profil zapasowy.")
                            logAgentDialogueForChat(requestChatId, cfg.name, "SYSTEM", "Limit lub awaria dostawcy: ${t.message}. Następny jest wyłącznie zweryfikowany darmowy model.", "TOKEN_LIMIT_FALLBACK")
                        } else {
                            TokenTracker.recordUsage(this, cfg.id, systemPrompt + "\n" + userText, "", "ERROR", t.message ?: "Błąd")
                            logAgentForChat(requestChatId, "⚠️ ${cfg.name}: błąd dostawcy/sieci. Próbuję zweryfikowanego darmowego profilu zapasowego.")
                        }
                        continue
                    } else {
                        if (isTokenOrRateLimit) {
                            TokenTracker.recordUsage(this, cfg.id, systemPrompt + "\n" + userText, "", "RATE_LIMITED_429", "Limit wyczerpany • 429 / Quota")
                            logAgentForChat(requestChatId, "⛔ ${cfg.name}: limit/quota wyczerpany i brak kolejnego zweryfikowanego darmowego modelu. Nie używam płatnego fallbacku.")
                        } else {
                            TokenTracker.recordUsage(this, cfg.id, systemPrompt + "\n" + userText, "", "ERROR", t.message ?: "Błąd")
                        }
                        throw t
                    }
                } finally {
                    lease.close()
                }
            }
            throw lastErr ?: IllegalStateException("Wszystkie skonfigurowane modele wyczerpały limity tokenów lub zawiodły.")
        } finally {
            if (ownedBudgetReservationId.isNotBlank()) {
                pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(ownedBudgetReservationId)
            }
        }
    }

    private fun sendChatMessage(input: EditText) {
        if (isThinking) { toast("Poczekaj na zakończenie poprzedniej odpowiedzi."); return }
        var prompt = input.text.toString().trim()
        if (attachedText.isNotBlank()) prompt += "\n\n[Załączony plik tekstowy: $attachedName]\n$attachedText"
        if (attachedName.isNotBlank() && attachedText.isBlank()) prompt += "\n\nZałącznik: $attachedName ($attachedMime). Przeanalizuj załącznik, jeśli ten dostawca obsługuje jego format."
        if (prompt.isBlank() && attachedName.isBlank()) { toast("Wpisz wiadomość lub dodaj plik."); return }
        val chosenRole = selectedAgent
        val config = if (chosenRole == AgentRole.MASTER) loadProvider(agentProviders[AgentRole.MASTER.name] ?: selectedProviderId) else loadProvider(agentProviders[chosenRole.name] ?: selectedProviderId)
        if (!providerReady(config)) { toast("Skonfiguruj adres, model i klucz API (lokalny Ollama nie wymaga klucza)."); return }
        val safeConfig = config!!

        val shownPrompt = if (attachedName.isNotBlank()) "${input.text.toString().trim()}\n\n📎 Załącznik: $attachedName".trim() else input.text.toString().trim()
        messages.add("user" to shownPrompt); persistMessages()
        val sendMime = attachedMime
        val sendBase64 = attachedBase64
        attachedText = ""; attachedName = ""; attachedMime = ""; attachedBase64 = ""
        draft = ""
        hideKeyboard(input)

        val chatId = currentChatId
        val gen = ++requestGen
        val requestId = UUID.randomUUID().toString()
        val requestTaskId = "chat-$chatId-$requestId"
        val requestStartedAt = System.currentTimeMillis()
        val modeForRequest = currentWorkMode()
        val responseScreen = if (screen == "Asystent") "Asystent" else "Czat"
        var actualProviderId = safeConfig.id
        var actualModel = safeConfig.model
        isThinking = true
        thinkingChatId = chatId
        thinkingLabel = if (chosenRole == AgentRole.MASTER) "MASTER analizuje wiadomość…" else "${chosenRole.label} analizuje wiadomość…"
        showShell(responseScreen)
        showChat()
        val requestScope = client.beginRequestScope()
        activeRequestScope = requestScope

        executor.execute {
            if (gen != requestGen || Thread.currentThread().isInterrupted) return@execute
            activeWorkerThread = Thread.currentThread()
            activeWorkerGeneration = gen
            val result = runCatching {
                if (chosenRole == AgentRole.MASTER) {
                    val mode = modeForRequest
                    val qualityEvidenceByAgent = pl.aimasterpro.app.core.QualityLearningStore.feedback(this)
                        .filter { it.targetType == "AGENT" && it.agentId.isNotBlank() }
                        .groupBy { it.agentId.lowercase(java.util.Locale.ROOT) }
                    val operationalEvidence = pl.aimasterpro.app.core.AgentEfficiencyTracker.load(this)
                    val routing = pl.aimasterpro.app.agents.AgentRoutingPolicy.route(prompt) { agentId, category ->
                        val humanQuality = pl.aimasterpro.app.core.QualityLearningStore.qualityScore(
                            qualityEvidenceByAgent[agentId.lowercase(java.util.Locale.ROOT)].orEmpty(), agentId, category
                        )
                        val operationalReliability = pl.aimasterpro.app.core.AgentEfficiencyTracker.reliabilityScore(
                            operationalEvidence, agentId
                        )
                        // Operational completion is not content quality. It contributes only 15% when
                        // both signals exist; either signal alone remains a conservative tie-breaker.
                        when {
                            humanQuality != null && operationalReliability != null -> humanQuality * 0.85 + operationalReliability * 0.15
                            humanQuality != null -> humanQuality
                            else -> operationalReliability
                        }
                    }
                    logAgentForChat(chatId, "MASTER ROUTING: ${routing.mode} • specjaliści=${routing.specialistCalls} • ${routing.reason}")
                    if (routing.mode == pl.aimasterpro.app.agents.AgentRoutingPolicy.Mode.MASTER_ONLY) {
                        runOnUiThread {
                            if (gen == requestGen) {
                                thinkingLabel = "MASTER odpowiada samodzielnie…"
                                thinkingText?.text = thinkingLabel
                            }
                        }
                        val memoryContext = pl.aimasterpro.app.core.AiMemoryStore.contextString(this, prompt, maxChars = 2_200)
                        val evidenceContext = buildMasterEvidenceContext(chatId)
                        logAgentForChat(chatId, "🧾 Dowody MASTER-a: ${evidenceContext.status} • rekordy=${evidenceContext.evidenceCount} • claims=${evidenceContext.agentClaimCount}.")
                        val directPrompt = buildString {
                            append(prompt)
                            if (memoryContext.isNotBlank()) append("\n\nPamięć ciągła:\n").append(memoryContext)
                            append("\n\n").append(evidenceContext.contextText)
                        }
                        val resp = chatWithFallback(
                            safeConfig,
                            AgentPrompts.systemPrompt(AgentRole.MASTER) + "\n\nZASADA ROUTINGU: Odpowiadaj samodzielnie. Nie uruchamiaj ani nie symuluj podagentów.",
                            directPrompt,
                            sendMime,
                            sendBase64,
                            requestTaskId,
                            chosenRole.name,
                            chatId,
                            requestScope
                        )
                        actualProviderId = resp.providerId.ifBlank { safeConfig.id }
                        actualModel = resp.model.ifBlank { safeConfig.model }
                        val check = pl.aimasterpro.app.core.MasterResponseCheck.evaluate(prompt, resp.text, evidenceContext.assessment)
                        if (check.warnings.isNotEmpty()) logAgentForChat(chatId, "🛡️ Kontrola MASTER-a: ${check.warnings.joinToString(" | ")}; bez dodatkowego wywołania API.")
                        else logAgentForChat(chatId, "🛡️ Kontrola MASTER-a: podstawowe kryteria kompletności zaliczone (lokalnie).")
                        resp.text + if (check.warnings.isNotEmpty()) "\n\n_Uwaga kontroli jakości: ${check.warnings.joinToString("; ")}_" else ""
                    } else {
                    val selectedSpecs = routing.agents
                    pl.aimasterpro.app.core.QualityLearningStore.syncApprovedAgentMemories(this)
                    val sharedLearningContext = pl.aimasterpro.app.core.AiMemoryStore.contextString(this, prompt, maxChars = 1_800)
                    val tasks = selectedSpecs.mapIndexed { index, spec ->
                        val agentMemory = pl.aimasterpro.app.core.AgentOperationalMemoryStore.contextString(
                            this, spec.id, prompt, maxChars = 900, maxItems = 4
                        )
                        val memoryBlock = listOf(sharedLearningContext, agentMemory).filter(String::isNotBlank).joinToString("\n\n")
                        TeamTask(
                            id = "task-$chatId-$requestId-${index + 1}-${spec.id}",
                            agentId = spec.id,
                            goal = "Niezależna analiza zadania z perspektywy: ${spec.name}",
                            prompt = "Zadanie użytkownika:\\n$prompt\\n\\nPamięć i zatwierdzone reguły:\\n$memoryBlock\\n\\nWykonaj specjalistyczną analizę z perspektywy swojej roli. Podaj konkretne ustalenia, ryzyka, niewiadome i zalecane działania. Wnioski z pamięci traktuj jako wskazówki, a nie niepodważalne fakty.",
                            writeScope = emptyList()
                        )
                    }
                    logAgentForChat(chatId, "MASTER: deleguję do ${tasks.size} specjalisty/specjalistów • tryb ${mode.label} • model ${safeConfig.model}")
                    runOnUiThread {
                        if (gen == requestGen) {
                            thinkingLabel = if (tasks.size == 1) "MASTER konsultuje jednego specjalistę…" else "MASTER koordynuje ${tasks.size} specjalistów…"
                            thinkingText?.text = thinkingLabel
                        }
                    }
                    // Specialist IDs and chat-role IDs are separate namespaces. Resolve each
                    // specialist to the matching user-configured role profile, but only use it
                    // when the profile is complete and explicitly classified as free/local.
                    // Otherwise use the already selected MASTER profile; TeamRunEngine performs
                    // a second independent free-model guard before every network request.
                    val batchAgentConfigsMap = tasks.mapNotNull { task ->
                        val spec = AgentCatalog.find(task.agentId) ?: return@mapNotNull null
                        val role = AgentProviderRouting.roleFor(spec)
                        val assignedId = agentProviders[role.name] ?: selectedProviderId
                        val assigned = loadProvider(assignedId)
                        val usableAssigned = assigned?.takeIf { candidate ->
                            providerReady(candidate) && isVerifiedFreeConfig(candidate)
                        }
                        task.agentId to (usableAssigned ?: safeConfig)
                    }.toMap()

                    val projectContext = pl.aimasterpro.app.core.ProjectCodebase.fullContext()
                    val explicitTeamRequest = listOf("cały zespół", "caly zespol", "wszyscy agenci", "uruchom cały zespół", "uruchom caly zespol").any(prompt.lowercase(java.util.Locale.ROOT)::contains)
                    val adaptivePlan = pl.aimasterpro.app.core.AdaptiveExecutionPolicy.plan(prompt, tasks.size, explicitTeamRequest, routing.mode == pl.aimasterpro.app.agents.AgentRoutingPolicy.Mode.TEAM)
                    val adaptiveTasks = tasks.take(adaptivePlan.maxAgents)
                    if (adaptiveTasks.size < tasks.size) logAgentForChat(chatId, "🧠 Adaptacyjny budżet: ${adaptivePlan.reason}; ograniczono liczbę specjalistów ${tasks.size} → ${adaptiveTasks.size}.")
                    else logAgentForChat(chatId, "🧠 Adaptacyjny budżet: ${adaptivePlan.reason}.")
                    val cacheFingerprints = adaptiveTasks.associate { task ->
                        val cfg = batchAgentConfigsMap[task.agentId] ?: safeConfig
                        val specialistPrompt = AgentCatalog.find(task.agentId)?.systemPrompt.orEmpty()
                        task.id to pl.aimasterpro.app.core.TaskResultCache.fingerprint(task.agentId,
                            task.goal + "\n" + specialistPrompt + "\n" + mode.systemInstruction, task.prompt,
                            projectContext, sendMime, sendBase64, cfg.id, cfg.baseUrl, cfg.model)
                    }
                    val cachedResults = adaptiveTasks.mapNotNull { task ->
                        val hit = pl.aimasterpro.app.core.TaskResultCache.get(this, cacheFingerprints.getValue(task.id)) ?: return@mapNotNull null
                        logAgentForChat(chatId, "♻️ Cache: wykorzystano aktualny raport ${task.agentId} (wiek ${((System.currentTimeMillis() - hit.createdAt) / 3_600_000L)} h); bez wywołania API.")
                        pl.aimasterpro.app.agents.TeamTaskResult(task.id, task.agentId,
                            pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED, hit.report, providerId = "CACHE", model = "LOCAL_CACHE")
                    }
                    val cachedTaskIds = cachedResults.map { it.taskId }.toSet()
                    val tasksToRun = adaptiveTasks.filterNot { it.id in cachedTaskIds }
                    val budgetRequests = tasksToRun.map { task ->
                        val spec = AgentCatalog.find(task.agentId)
                        val cfg = batchAgentConfigsMap[task.agentId] ?: safeConfig
                        val inputChars = (spec?.systemPrompt.orEmpty().length + mode.systemInstruction.length +
                            projectContext.length + task.prompt.length + 1800)
                        pl.aimasterpro.app.core.BudgetRequest(
                            taskId = task.id,
                            agentId = task.agentId,
                            providerId = cfg.id,
                            baseUrl = cfg.baseUrl,
                            modelName = cfg.model,
                            estimatedInputTokens = ((inputChars + 2) / 3).toLong(),
                            estimatedOutputTokens = adaptivePlan.outputTokensPerAgent
                        )
                    }
                    val synthesisReportBudgetChars = minOf(18_000, adaptiveTasks.size * 6_000)
                    val masterSynthesisReserve = ((prompt.length + sharedLearningContext.length + synthesisReportBudgetChars + 1_200 + 2) / 3).toLong() + adaptivePlan.synthesisReserveTokens
                    val budgetPlan = if (budgetRequests.isEmpty()) pl.aimasterpro.app.core.BatchBudgetPlan(
                        allowedTaskIds = emptySet(), blockedReasons = emptyMap(), estimatedReservedTokens = 0L,
                        estimatedReservedCostPln = 0.0, reservationId = ""
                    ) else pl.aimasterpro.app.core.ApiTokenTracker.planBatch(
                        this, budgetRequests, globalReserveTokens = masterSynthesisReserve
                    )
                    budgetPlan.blockedReasons.forEach { (taskId, reason) ->
                        logAgentForChat(chatId, "⛔ Smart Budget: $taskId — $reason")
                    }
                    val budgetedTasks = tasksToRun.filter { it.id in budgetPlan.allowedTaskIds }
                    if (budgetedTasks.isEmpty() && cachedResults.isEmpty()) {
                        pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(budgetPlan.reservationId)
                        val reasons = budgetPlan.blockedReasons.values.distinct().joinToString(" | ").ifBlank { "Nie udało się zarezerwować budżetu dla żadnego zadania." }
                        val usage = pl.aimasterpro.app.core.ApiTokenTracker.budgetUsageSnapshot(this)
                        throw IllegalStateException(
                            "Smart Budget wstrzymał zespół.\nPowód: $reasons\n" +
                                "Dziennie: ${usage.recordedTodayTokens + usage.activeReservedTokens}/${usage.dailyLimitTokens} tokenów; " +
                                "pozostało ${usage.remainingTokens}.\n" +
                                "Otwórz zakładkę Diagnostyka lub Monitor API → Limity budżetowe."
                        )
                    }
                    logAgentForChat(chatId, "🧠 Smart Budget: cache=${cachedResults.size}, nowe wywołania=${budgetedTasks.size}/${tasksToRun.size}; rezerwa=${budgetPlan.estimatedReservedTokens} tokenów + synteza MASTER-a.")

                    val freshResults = try {
                        if (budgetedTasks.isEmpty()) emptyList() else TeamRunEngine(client, maxParallel = adaptivePlan.maxAgents.coerceAtLeast(1)).runBatch(
                            tasks = budgetedTasks,
                            provider = safeConfig,
                            agentProvidersMap = batchAgentConfigsMap,
                            fallbackProviders = listOf(safeConfig),
                            isFreeProvider = { candidate -> isVerifiedFreeConfig(candidate) },
                            mode = mode,
                            projectContext = projectContext,
                            attachmentMime = sendMime,
                            attachmentBase64 = sendBase64,
                            onLog = { sender, recipient, msg, status -> logAgentDialogueForChat(chatId, sender, recipient, msg, status) },
                            onUsage = { agentId, taskId, actualProvider, usage ->
                                pl.aimasterpro.app.core.ApiTokenTracker.recordQuery(this, actualProvider.id, actualProvider.model, agentId, taskId, usage.inputTokens, usage.outputTokens, usage.reasoningTokens, usage.cachedTokens, usage.status, actualProvider.baseUrl, budgetPlan.reservationId)
                            },
                            rankProviders = { candidates -> pl.aimasterpro.app.core.ModelHealthTracker.rank(this, candidates) },
                            onProviderHealth = { model, success, category, latency ->
                                pl.aimasterpro.app.core.ModelHealthTracker.record(this, model, success, category, latency)
                            },
                            persistedCooldownMs = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.remainingMs(this, model) }.getOrDefault(0L) },
                            onRateLimited = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.markRateLimited(this, model) }.getOrDefault(0L) },
                            onProviderSuccess = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.markSuccess(this, model) } },
                            onTaskState = { task, state, detail, providerId, model, attempts ->
                                val saved = pl.aimasterpro.app.core.TaskCheckpointStore.record(this,
                                    pl.aimasterpro.app.core.TaskCheckpointStore.Event(task.id, task.agentId, state, detail,
                                        System.currentTimeMillis(), providerId, model, attempts))
                                if (!saved) android.util.Log.e("AIMasterRecovery", "Nie udało się trwale zapisać checkpointu zadania.")
                            },
                            requestScope = requestScope,
                            onTaskFinished = { taskId -> pl.aimasterpro.app.core.ApiTokenTracker.completeReservationTask(budgetPlan.reservationId, taskId) }
                        )
                    } finally {
                        if (budgetPlan.reservationId.isNotBlank()) pl.aimasterpro.app.core.ApiTokenTracker.requestBatchReservationRelease(budgetPlan.reservationId)
                    }
                    freshResults.filter { it.status == pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED }.forEach { fresh ->
                        val fingerprint = cacheFingerprints[fresh.taskId] ?: return@forEach
                        val configured = batchAgentConfigsMap[fresh.agentId] ?: safeConfig
                        // Do not poison the configured-profile cache with a report produced by fallback.
                        // The actual fallback may change between runs and is not knowable at lookup time.
                        if (fresh.providerId == configured.id && fresh.model == configured.model) {
                            pl.aimasterpro.app.core.TaskResultCache.put(this, fingerprint, fresh.report)
                        } else {
                            logAgentForChat(chatId, "♻️ Cache pominięty dla ${fresh.agentId}: raport pochodzi z fallbacku ${fresh.providerId}/${fresh.model}, a nie z przypisanego profilu ${configured.id}/${configured.model}.")
                        }
                    }
                    val results = cachedResults + freshResults
                    val recordedTokenRows = pl.aimasterpro.app.core.ApiTokenTracker.loadAllRecordsFromStorage(this)
                    results.forEach { result ->
                        if (result.providerId != "CACHE") {
                        pl.aimasterpro.app.core.AgentEfficiencyTracker.record(this,
                            pl.aimasterpro.app.core.AgentEfficiencyTracker.Outcome(
                                timestamp = System.currentTimeMillis(), taskId = result.taskId, agentId = result.agentId,
                                status = result.status.name, durationMs = result.durationMs, attempts = result.attempts,
                                providerId = result.providerId, model = result.model,
                                errorCategory = if (result.status == pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED) "" else result.report.substringBefore(":").take(60)
                            )
                        )
                        pl.aimasterpro.app.core.LearningJournalStore.record(this,
                            pl.aimasterpro.app.core.LearningJournalStore.Event(
                                timestamp = System.currentTimeMillis(), kind = "agent", agentId = result.agentId,
                                providerId = result.providerId, model = result.model, status = result.status.name,
                                durationMs = result.durationMs, attempts = result.attempts,
                                category = if (result.status == pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED)
                                    (AgentCatalog.find(result.agentId)?.category ?: "") else "agent_failure",
                                taskId = result.taskId,
                                inputTokens = recordedTokenRows.filter { it.taskId == result.taskId }.sumOf { it.inputTokens.coerceAtLeast(0L) },
                                outputTokens = recordedTokenRows.filter { it.taskId == result.taskId }.sumOf { it.outputTokens.coerceAtLeast(0L) }
                            )
                        )
                        }
                        logAgentForChat(chatId, "${result.agentId}: ${result.status}${if (result.providerId == "CACHE") " • CACHE" else ""} • ${result.report.take(180).replace("\\n", " ")}")
                    }
                    val completed = results.filter { it.status == pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED }
                    if (completed.isEmpty()) {
                        throw IllegalStateException("Żaden agent nie zwrócił poprawnego raportu. " +
                            results.joinToString("\\n") { "${it.agentId}: ${it.report}" })
                    }
                    // Stage 2: real peer review. Reviewers receive another agent's actual report
                    // and return an independent critique through a separate provider request.
                    // Keep the round intentionally small (max two reviews) and budget it separately.
                    runOnUiThread {
                        if (gen == requestGen) {
                            thinkingLabel = "Agenci zakończyli analizę. MASTER uruchamia wzajemną recenzję…"
                            thinkingText?.text = thinkingLabel
                        }
                    }
                    val peerReviewTasks: List<TeamTask> = pl.aimasterpro.app.agents.PeerReviewPlanner.createTasks(
                        completedReports = completed,
                        selectedAgents = selectedSpecs,
                        chatId = chatId,
                        requestId = requestId,
                        originalPrompt = prompt,
                        maxReviews = 2
                    )

                    val peerReviewResults: List<pl.aimasterpro.app.agents.TeamTaskResult> = if (peerReviewTasks.isEmpty()) {
                        logAgentForChat(chatId, "🤝 Peer Review pominięty: potrzeba co najmniej dwóch poprawnych raportów i dwóch dostępnych specjalistów.")
                        emptyList<pl.aimasterpro.app.agents.TeamTaskResult>()
                    } else {
                        val reviewBudgetRequests = peerReviewTasks.map { task ->
                            val spec = AgentCatalog.find(task.agentId)
                            val cfg = batchAgentConfigsMap[task.agentId] ?: safeConfig
                            val inputChars = (spec?.systemPrompt.orEmpty().length + mode.systemInstruction.length + task.prompt.length + 1_200)
                            pl.aimasterpro.app.core.BudgetRequest(
                                taskId = task.id,
                                agentId = task.agentId,
                                providerId = cfg.id,
                                baseUrl = cfg.baseUrl,
                                modelName = cfg.model,
                                estimatedInputTokens = ((inputChars + 2) / 3).toLong(),
                                estimatedOutputTokens = minOf(adaptivePlan.outputTokensPerAgent.coerceAtLeast(500L), 1_200L)
                            )
                        }
                        val reviewBudget = pl.aimasterpro.app.core.ApiTokenTracker.planBatch(
                            this,
                            reviewBudgetRequests,
                            globalReserveTokens = masterSynthesisReserve + peerReviewTasks.size * 1_000L
                        )
                        reviewBudget.blockedReasons.forEach { (taskId, reason) ->
                            logAgentForChat(chatId, "⛔ Peer Review — Smart Budget zablokował $taskId: $reason")
                        }
                        val allowedReviews = peerReviewTasks.filter { it.id in reviewBudget.allowedTaskIds }
                        if (allowedReviews.isEmpty()) {
                            pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(reviewBudget.reservationId)
                            logAgentForChat(chatId, "🤝 Peer Review pominięty: brak bezpiecznego budżetu po zachowaniu rezerwy na syntezę MASTER-a.")
                            emptyList<pl.aimasterpro.app.agents.TeamTaskResult>()
                        } else {
                            logAgentForChat(chatId, "🤝 Peer Review: uruchamiam ${allowedReviews.size}/${peerReviewTasks.size} niezależnych recenzji. Każdy recenzent otrzymuje raport innego agenta; dodatkowe wywołania są objęte Smart Budget.")
                            try {
                                TeamRunEngine(client, maxParallel = minOf(2, adaptivePlan.maxAgents.coerceAtLeast(1))).runBatch(
                                    tasks = allowedReviews,
                                    provider = safeConfig,
                                    agentProvidersMap = batchAgentConfigsMap,
                                    fallbackProviders = listOf(safeConfig),
                                    isFreeProvider = { candidate -> isVerifiedFreeConfig(candidate) },
                                    mode = mode,
                                    projectContext = projectContext,
                                    attachmentMime = "",
                                    attachmentBase64 = "",
                                    onLog = { sender, recipient, msg, eventStatus -> logAgentDialogueForChat(chatId, sender, recipient, msg, eventStatus) },
                                    onUsage = { agentId, taskId, actualProvider, usage ->
                                        pl.aimasterpro.app.core.ApiTokenTracker.recordQuery(
                                            this, actualProvider.id, actualProvider.model, agentId, taskId,
                                            usage.inputTokens, usage.outputTokens, usage.reasoningTokens,
                                            usage.cachedTokens, usage.status, actualProvider.baseUrl, reviewBudget.reservationId
                                        )
                                    },
                                    rankProviders = { candidates -> pl.aimasterpro.app.core.ModelHealthTracker.rank(this, candidates) },
                                    onProviderHealth = { model, success, category, latency ->
                                        pl.aimasterpro.app.core.ModelHealthTracker.record(this, model, success, category, latency)
                                    },
                                    persistedCooldownMs = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.remainingMs(this, model) }.getOrDefault(0L) },
                                    onRateLimited = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.markRateLimited(this, model) }.getOrDefault(0L) },
                                    onProviderSuccess = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.markSuccess(this, model) } },
                                    onTaskState = { task, state, detail, providerId, model, attempts ->
                                        val saved = pl.aimasterpro.app.core.TaskCheckpointStore.record(this,
                                            pl.aimasterpro.app.core.TaskCheckpointStore.Event(task.id, task.agentId, state, detail,
                                                System.currentTimeMillis(), providerId, model, attempts))
                                        if (!saved) android.util.Log.e("AIMasterRecovery", "Nie udało się trwale zapisać checkpointu zadania.")
                                    },
                                    requestScope = requestScope,
                                    onTaskFinished = { taskId -> pl.aimasterpro.app.core.ApiTokenTracker.completeReservationTask(reviewBudget.reservationId, taskId) }
                                )
                            } catch (reviewError: Throwable) {
                                logAgentForChat(chatId, "⚠️ Peer Review nie powiódł się; zachowuję raporty pierwszego etapu. ${reviewError.message ?: reviewError.javaClass.simpleName}")
                                emptyList<pl.aimasterpro.app.agents.TeamTaskResult>()
                            } finally {
                                if (reviewBudget.reservationId.isNotBlank()) pl.aimasterpro.app.core.ApiTokenTracker.requestBatchReservationRelease(reviewBudget.reservationId)
                            }
                        }
                    }
                    val reviewUsageRows = pl.aimasterpro.app.core.ApiTokenTracker.loadAllRecordsFromStorage(this)
                    peerReviewResults.forEach { review ->
                        val reviewTarget = review.reviewOfAgentId.ifBlank { "nieustalony raport" }
                        logAgentForChat(chatId, "🤝 Peer Review ${review.agentId} → $reviewTarget: ${review.status} • ${review.report.take(180).replace("\n", " ")}")
                        pl.aimasterpro.app.core.AgentEfficiencyTracker.record(this,
                            pl.aimasterpro.app.core.AgentEfficiencyTracker.Outcome(
                                timestamp = System.currentTimeMillis(), taskId = review.taskId, agentId = review.agentId,
                                status = review.status.name, durationMs = review.durationMs, attempts = review.attempts,
                                providerId = review.providerId, model = review.model,
                                errorCategory = if (review.status == pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED) "" else review.report.substringBefore(":").take(60)
                            )
                        )
                        pl.aimasterpro.app.core.LearningJournalStore.record(this,
                            pl.aimasterpro.app.core.LearningJournalStore.Event(
                                timestamp = System.currentTimeMillis(), kind = "peer_review", agentId = review.agentId,
                                providerId = review.providerId, model = review.model, status = review.status.name,
                                durationMs = review.durationMs, attempts = review.attempts,
                                category = if (review.status == pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED) "peer_review" else "peer_review_failure",
                                taskId = review.taskId,
                                inputTokens = reviewUsageRows.filter { it.taskId == review.taskId }.sumOf { it.inputTokens.coerceAtLeast(0L) },
                                outputTokens = reviewUsageRows.filter { it.taskId == review.taskId }.sumOf { it.outputTokens.coerceAtLeast(0L) }
                            )
                        )
                    }
                    val synthesisResults = results + peerReviewResults
                    runOnUiThread {
                        if (gen == requestGen) {
                            thinkingLabel = "MASTER scala ${completed.size} raportów i ${peerReviewResults.count { it.status == pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED }} recenzji…"
                            thinkingText?.text = thinkingLabel
                        }
                    }
                    val memoryContext = pl.aimasterpro.app.core.AiMemoryStore.contextString(this, prompt, maxChars = 2_200)
                    val reportPack = pl.aimasterpro.app.agents.SynthesisContextBuilder.build(synthesisResults, maxPerReportChars = 6_000, maxTotalChars = 18_000)
                    val reports = reportPack.text
                    // Numeric conflict detection compares independent specialist findings only.
                    // Peer reviews are critiques of existing reports, not additional independent
                    // evidence; mixing them here can create false conflicts or duplicate sources.
                    val detectedConflicts = pl.aimasterpro.app.core.ReportConflictDetector.detect(
                        completed.map { pl.aimasterpro.app.core.ReportConflictDetector.Report(it.agentId, it.report) }
                    )
                    val conflictInstruction = pl.aimasterpro.app.core.ReportConflictDetector.toMasterInstruction(detectedConflicts)
                    if (detectedConflicts.isNotEmpty()) {
                        logAgentForChat(chatId, "⚠️ Wykryto ${detectedConflicts.size} możliwych rozbieżności liczbowych między agentami. MASTER ma je rozstrzygnąć dowodami albo jawnie oznaczyć jako nierozstrzygnięte; bez dodatkowych wywołań API.")
                    } else {
                        logAgentForChat(chatId, "🔎 Kontrola zgodności: brak wykrytych bezpośrednich konfliktów liczbowych (nie oznacza pełnej weryfikacji faktów).")
                    }
                    if (reportPack.excludedReports > 0 || reportPack.truncatedReports > 0) {
                        logAgentForChat(chatId, "🧠 Ochrona kontekstu syntezy: raporty poprawne=${reportPack.includedReports}, pominięte/nieudane=${reportPack.excludedReports}, skrócone=${reportPack.truncatedReports}, źródłowo=${reportPack.sourceChars} znaków, przekazano=${reportPack.text.length} znaków.")
                    }
                    val evidenceContext = buildMasterEvidenceContext(chatId)
                    logAgentForChat(chatId, "🧾 Dowody MASTER-a: ${evidenceContext.status} • rekordy=${evidenceContext.evidenceCount} • claims=${evidenceContext.agentClaimCount}.")
                    val autonomousSynthesisPrompt = """
                        Zadanie użytkownika:
                        $prompt

                        Rejestr dowodów weryfikacji (ograniczony do jawnie wskazanego zakresu):
                        ${evidenceContext.contextText}

                        Pamięć ciągła i reguły projektu:
                        $memoryContext

                        Raporty podagentów (specjalistów):
                        $reports

                        Wynik lokalnej kontroli konfliktów:
                        $conflictInstruction

                        Zasady wykorzystania recenzji:
                        Uwzględnij raporty oznaczone jako recenzje koleżeńskie (Peer Review). Traktuj je jako krytyczną opinię, a nie automatyczny fakt. Rozstrzygaj spory na podstawie konkretnych danych i dowodów. Nie ukrywaj nierozstrzygniętych rozbieżności. Jeśli Peer Review został pominięty z powodu budżetu lub liczby agentów, nie twierdź, że taka recenzja się odbyła.

                        Instrukcja autonomiczna (Self-Correction & Peer Review):
                        Przeanalizuj raporty podagentów pod kątem spójności, kompletności i potencjalnych błędów. Jeśli występuje sprzeczność lub brak kluczowego szczegółu technicznego, skoryguj go na podstawie pamięci projektu. Przygotuj jedną, ostateczną i gotową do użycia odpowiedź po polsku. Nazywaj wynik zweryfikowanym tylko wtedy, gdy przekazany ledger zawiera komplet wymaganych dowodów PASS. Status COMPLETED podagenta oznacza wyłącznie dostarczenie raportu. Gdy dowodów brak, napisz wprost, że weryfikacja nie została potwierdzona lub ma status NOT_RUN.
                    """.trimIndent()

                    val masterSynthesisSystem = AgentPrompts.systemPrompt(AgentRole.MASTER) +
                        "\n\nAKTYWNY TRYB: ${mode.label}\n${mode.systemInstruction}\n[AUTONOMOUS_MODE]: Aktywny tryb samorefleksji, pamięci długoterminowej i weryfikacji krzyżowej."
                    logAgentDialogueForChat(
                        chatId,
                        "MASTER",
                        "${safeConfig.name} / ${safeConfig.model}",
                        "[MASTER SYNTHESIS REQUEST]\n--- SYSTEM ---\n$masterSynthesisSystem\n--- USER ---\n$autonomousSynthesisPrompt\nAttachment MIME: ${sendMime.ifBlank { "brak" }}\nAttachment payload: ${if (sendBase64.isBlank()) "brak" else "dołączony; treść binarna pominięta w dzienniku"}",
                        "REQUEST"
                    )
                    // Reserve the final MASTER call independently after specialist and review
                    // usage has been recorded. Earlier stage reservations are intentionally released
                    // when those stages finish, so they cannot protect this later synthesis request.
                    val synthesisBudget = pl.aimasterpro.app.core.ApiTokenTracker.planBatch(
                        this,
                        listOf(pl.aimasterpro.app.core.BudgetRequest(
                            taskId = requestTaskId,
                            agentId = AgentRole.MASTER.name,
                            providerId = safeConfig.id,
                            baseUrl = safeConfig.baseUrl,
                            modelName = safeConfig.model,
                            estimatedInputTokens = ((masterSynthesisSystem.length + autonomousSynthesisPrompt.length + 2) / 3).toLong(),
                            estimatedOutputTokens = 4_096L
                        ))
                    )
                    if (requestTaskId !in synthesisBudget.allowedTaskIds) {
                        pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(synthesisBudget.reservationId)
                        val reason = synthesisBudget.blockedReasons[requestTaskId] ?: "Nie udało się zarezerwować budżetu na syntezę MASTER-a."
                        throw IllegalStateException("Smart Budget wstrzymał końcową syntezę: $reason")
                    }
                    logAgentForChat(chatId, "🧠 Smart Budget: zarezerwowano osobny limit dla końcowej syntezy MASTER-a.")
                    val resp = try {
                        chatWithFallback(
                            safeConfig,
                            masterSynthesisSystem,
                            autonomousSynthesisPrompt,
                            sendMime,
                            sendBase64,
                            requestTaskId,
                            AgentRole.MASTER.name,
                            chatId,
                            requestScope,
                            synthesisBudget.reservationId
                        )
                    } finally {
                        pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(synthesisBudget.reservationId)
                    }
                    actualProviderId = resp.providerId.ifBlank { safeConfig.id }
                    actualModel = resp.model.ifBlank { safeConfig.model }
                    logAgentDialogueForChat(chatId, "$actualProviderId / $actualModel", "MASTER", resp.text, "REPORT")
                    val check = pl.aimasterpro.app.core.MasterResponseCheck.evaluate(prompt, resp.text, evidenceContext.assessment)
                    if (check.warnings.isNotEmpty()) logAgentForChat(chatId, "🛡️ Kontrola MASTER-a: ${check.warnings.joinToString(" | ")}; odpowiedź nie została automatycznie ponownie wysłana, aby nie zużywać dodatkowych tokenów.")
                    else logAgentForChat(chatId, "🛡️ Kontrola MASTER-a: podstawowe kryteria kompletności zaliczone (kontrola lokalna, bez dodatkowego API).")
                    resp.text + if (check.warnings.isNotEmpty()) "\n\n_Uwaga kontroli jakości: ${check.warnings.joinToString("; ")}_" else ""
                    }
                } else {
                    val mode = modeForRequest
                    val memoryContext = pl.aimasterpro.app.core.AiMemoryStore.contextString(this, prompt, maxChars = 2_200)
                    val singlePrompt = "$prompt\n\nPamięć ciągła:\n$memoryContext"
                    val resp = chatWithFallback(safeConfig, AgentPrompts.systemPrompt(chosenRole) + "\n\nAKTYWNY TRYB: ${mode.label}\n${mode.systemInstruction}", singlePrompt, sendMime, sendBase64, requestTaskId, chosenRole.name, chatId, requestScope)
                    actualProviderId = resp.providerId.ifBlank { safeConfig.id }
                    actualModel = resp.model.ifBlank { safeConfig.model }
                    resp.text
                }
            }
            runOnUiThread {
                if (gen != requestGen) {
                    if (activeWorkerGeneration == gen) {
                        activeWorkerGeneration = -1
                        activeWorkerThread = null
                        if (activeRequestScope === requestScope) activeRequestScope = null
                    }
                    return@runOnUiThread // użytkownik zatrzymał generowanie
                }
                if (activeWorkerGeneration == gen) {
                    activeWorkerGeneration = -1
                    activeWorkerThread = null
                    if (activeRequestScope === requestScope) activeRequestScope = null
                }
                isThinking = false
                val ok = result.isSuccess
                val chatTokenRows = pl.aimasterpro.app.core.ApiTokenTracker.loadAllRecordsFromStorage(this).filter { it.taskId == requestTaskId }
                pl.aimasterpro.app.core.LearningJournalStore.record(this,
                    pl.aimasterpro.app.core.LearningJournalStore.Event(
                        timestamp = System.currentTimeMillis(), kind = "chat", agentId = chosenRole.name,
                        providerId = actualProviderId, model = actualModel,
                        status = if (ok) "COMPLETED" else "FAILED", durationMs = (System.currentTimeMillis() - requestStartedAt).coerceAtLeast(0L), attempts = 0,
                        category = if (ok) "" else ((result.exceptionOrNull() as? ProviderException)?.category ?: (result.exceptionOrNull()?.javaClass?.simpleName ?: "unknown_error")),
                        taskId = requestTaskId,
                        inputTokens = chatTokenRows.sumOf { it.inputTokens.coerceAtLeast(0L) },
                        outputTokens = chatTokenRows.sumOf { it.outputTokens.coerceAtLeast(0L) }
                    )
                )
                val reply: String
                if (ok) {
                    reply = result.getOrNull().orEmpty()
                    logAgentForChat(chatId, "${chosenRole.label}: odpowiedź odebrana • $actualProviderId/$actualModel")
                } else {
                    val e = result.exceptionOrNull()
                    val detail = if (e is ProviderException) "${e.message}\n${e.responseBody}" else (e?.message ?: "Nieznany błąd")
                    reply = "Nie udało się uzyskać odpowiedzi.\n\n$detail\n\nSprawdź adres, model, klucz API i limit dostawcy."
                    logAgentForChat(chatId, "${chosenRole.label}: błąd dostawcy • $detail")
                }
                if (chatId == currentChatId) {
                    messages.add("assistant" to reply)
                    assistantTaskIds.add(requestTaskId)
                    persistMessages()
                } else appendToStoredChat(chatId, "assistant", reply, requestTaskId)
                if (chatId == currentChatId && screen == responseScreen) {
                    showShell(responseScreen)
                    showChat()
                    if (!ok) { status.text = "Błąd połączenia • sprawdź Diagnostykę"; status.setTextColor(Color.RED) }
                }
            }
        }
    }

    // =====================================================================
    //  Dialogi: wybór agenta/modelu, menu rozmowy, opcje rozmowy
    // =====================================================================

    private fun pickerRow(title: String, sub: String, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(14), dp(10), dp(14), dp(10))
        row.background = ripple(Color.TRANSPARENT, 12)
        row.setOnClickListener { onClick() }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = title
        t.textSize = 16f
        t.setTextColor(white)
        col.addView(t)
        if (sub.isNotBlank()) {
            val s = TextView(this)
            s.text = sub
            s.textSize = 12f
            s.setTextColor(muted)
            col.addView(s)
        }
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) row.addView(icon(R.drawable.ic_check, 20, white, 0))
        return row
    }

    private fun pickerHeader(text: String): View {
        val h = TextView(this)
        h.text = text
        h.textSize = 12f
        h.typeface = Typeface.DEFAULT_BOLD
        h.setTextColor(muted)
        h.setPadding(dp(14), dp(14), dp(14), dp(4))
        return h
    }

    private fun showModelPicker() {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(6), dp(6), dp(6), dp(10))
        val dialog = AlertDialog.Builder(this).create()
        box.addView(pickerHeader("Agent"))
        AgentRole.values().forEach { role ->
            box.addView(pickerRow(role.label, role.purpose, role == selectedAgent) {
                selectedAgent = role; dialog.dismiss(); navigate("Czat")
            })
        }
        box.addView(pickerHeader("Model dla MASTER"))
        val configs = store.allConfigs().mapNotNull { j -> runCatching { loadProvider(j.optString("id")) }.getOrNull() }.filter { providerReady(it) && isModelPresentInCatalog(it) }
        configs.forEach { p ->
            val sub = p.model + if (p.apiKey.isBlank() && !p.id.equals("ollama", true)) " • brak klucza API" else if (p.id.equals("ollama", true)) " • lokalny / bez klucza" else ""
            box.addView(pickerRow(p.name, sub, p.id == selectedProviderId) {
                selectedProviderId = p.id
                agentProviders[AgentRole.MASTER.name] = p.id
                chatPrefs.edit().putString("selected_provider", p.id)
                    .putString("agent_provider_${AgentRole.MASTER.name}", p.id).apply()
                dialog.dismiss(); navigate("Czat")
            })
        }
        box.addView(pickerRow("+ Dodaj nowy model / endpoint…", "", false) {
            dialog.dismiss()
            providerDialog(ProviderConfig("custom_" + System.currentTimeMillis(), "Nowy Model", ProviderKind.OPENAI_COMPATIBLE, "", "", ""))
        })
        box.addView(pickerRow("Zarządzaj modelami i kluczami…", "", false) { dialog.dismiss(); navigate("Modele") })
        val scroll = ScrollView(this)
        scroll.addView(box)
        dialog.setView(scroll)
        dialog.window?.setBackgroundDrawable(rounded(panel, 20))
        dialog.show()
    }

    private fun showChatMenu() {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        fun add(label: String, f: () -> Unit) { items.add(label to f) }
        if (screen == "Czat") {
            add("Nowy czat") { newChat() }
            if (messages.isNotEmpty()) {
                add("Kopiuj całą rozmowę") { copyConversation() }
                add("Eksportuj rozmowę do pliku (.md)") { exportChatToFile() }
                add("Zmień nazwę rozmowy") { renameChat(currentChatId) }
                add(if (isPinned(currentChatId)) "Odepnij rozmowę" else "Przypnij rozmowę") { togglePinned(currentChatId); toast(if (isPinned(currentChatId)) "Przypięto rozmowę." else "Odpięto rozmowę.") }
                add("Usuń rozmowę") { deleteChat(currentChatId) }
            }
        }
        add("Biblioteka plików") { showLibrary() }
        add("Wczytaj bazę modeli") { chooseBackupToImport() }
        add("Eksportuj bazę modeli") { promptBackupPassword(export = true) }
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }.show()
    }

    private fun showChatOptions(id: String) {
        val items = mutableListOf<Pair<String, () -> Unit>>()
        fun add(label: String, f: () -> Unit) { items.add(label to f) }
        add(if (isPinned(id)) "Odepnij" else "Przypnij") { togglePinned(id); populateDrawerList() }
        add("Zmień nazwę") { renameChat(id) }
        add("Usuń") { deleteChat(id) }
        AlertDialog.Builder(this).setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }.show()
    }

    private fun renameChat(id: String) {
        if (id.isBlank()) return
        val root0 = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val old = root0.optJSONObject("chats")?.optJSONObject(id)?.optString("title") ?: chatTitles[id].orEmpty()
        val input = field("Nazwa rozmowy", old)
        val box = LinearLayout(this)
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(input, LinearLayout.LayoutParams(-1, -2))
        AlertDialog.Builder(this).setTitle("Zmień nazwę rozmowy").setView(box)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Zapisz") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                synchronized(this) {
                    val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
                    val chat = r.optJSONObject("chats")?.optJSONObject(id)
                    if (chat != null) { chat.put("title", name); chatPrefs.edit().putString("chats_v2", r.toString()).apply() }
                }
                chatTitles[id] = name
                populateDrawerList()
                toast("Zmieniono nazwę rozmowy.")
            }.show()
    }

    private fun deleteChat(id: String) {
        if (id.isBlank()) return
        AlertDialog.Builder(this).setTitle("Usunąć rozmowę?").setMessage("Tej operacji nie można cofnąć.")
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń") { _, _ ->
                val deleted = synchronized(this) {
                    if (id == currentChatId && !invalidateActiveEvidenceScopeForChatSwitch()) {
                        false
                    } else {
                        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
                        r.optJSONObject("chats")?.remove(id)
                        chatPrefs.edit().putString("chats_v2", r.toString()).apply()
                        chatTitles.remove(id)
                        val pins = pinnedIds(); if (pins.remove(id)) chatPrefs.edit().putStringSet("pinned_chats", pins).apply()
                        if (id == currentChatId) {
                            currentChatId = UUID.randomUUID().toString()
                            messages.clear()
                            assistantTaskIds.clear()
                            saveCurrentId()
                            navigate("Czat")
                        } else populateDrawerList()
                        true
                    }
                }
                if (!deleted) {
                    toast("Nie można bezpiecznie unieważnić aktywnego zakresu dowodów. Rozmowa nie została usunięta.")
                    return@setPositiveButton
                }
                toast("Usunięto rozmowę.")
            }.show()
    }

    private fun exportChatToFile() {
        pendingExportText = messages.joinToString("\n\n---\n\n") { (role, t) -> (if (role == "user") "### Ty:\n" else "### AI Master:\n") + t }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/markdown"
            putExtra(Intent.EXTRA_TITLE, "ai-master-chat-${System.currentTimeMillis()}.md")
        }
        startActivityForResult(intent, EXPORT_CHAT_FILE)
    }

    private fun copyConversation() {
        val text = messages.joinToString("\n\n") { (role, t) -> (if (role == "user") "Ty:\n" else "Asystent:\n") + t }
        copyText(text)
    }

    // =====================================================================
    //  Przechowywanie rozmów
    // =====================================================================

    private fun listChats(): List<ChatMeta> {
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chats = r.optJSONObject("chats") ?: return emptyList()
        val out = mutableListOf<ChatMeta>()
        for (id in chats.keys()) {
            val o = chats.optJSONObject(id) ?: continue
            val arr = o.optJSONArray("messages") ?: continue
            val n = arr.length()
            if (n == 0) continue
            var title = o.optString("title", "")
            if (title.isBlank() || title == "Nowa rozmowa") {
                // Starsze wersje zapisywały wszystkim rozmowom ten sam tytuł; wyliczamy go z pierwszej wiadomości.
                for (k in 0 until n) {
                    val m = arr.optJSONObject(k) ?: continue
                    if (m.optString("role") == "user") { title = titleFromText(m.optString("text")); break }
                }
            }
            out.add(ChatMeta(id, title.ifBlank { "Rozmowa" }, o.optLong("updatedAt", 0L), n))
        }
        return out.sortedByDescending { it.updatedAt }
    }

    @Synchronized private fun openChat(id: String) {
        if (id != currentChatId && !invalidateActiveEvidenceScopeForChatSwitch()) {
            toast("Nie można bezpiecznie unieważnić aktywnego zakresu dowodów. Zmiana czatu została zablokowana.")
            return
        }
        currentChatId = id
        messages.clear()
        assistantTaskIds.clear()
        agentActivity.clear()
        draft = ""
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chatObj = r.optJSONObject("chats")?.optJSONObject(id)
        val arr = chatObj?.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) { val item = arr.getJSONObject(i); messages.add(item.optString("role") to item.optString("text")) }
        val taskIds = chatObj?.optJSONArray("assistantTaskIds") ?: JSONArray()
        for (i in 0 until taskIds.length()) assistantTaskIds.add(taskIds.optString(i))
        alignAssistantTaskIds()
        val actArr = chatObj?.optJSONArray("agentActivity") ?: JSONArray()
        for (i in 0 until actArr.length()) { agentActivity.add(actArr.getString(i)) }
        saveCurrentId()
        if (isAssistantChatId(id)) {
            selectedAgent = AgentRole.ASSISTANT
            showShell("Asystent")
            showChat()
        } else {
            navigate("Czat")
        }
    }

    private fun deriveTitle(): String =
        messages.firstOrNull { it.first == "user" }?.second?.let { titleFromText(it) } ?: "Nowa rozmowa"

    private fun titleFromText(first: String): String {
        val clean = first.substringBefore("📎 Załącznik:").trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (clean.isBlank()) return "Rozmowa z załącznikiem"
        return if (clean.length > 48) clean.take(48).trimEnd() + "…" else clean
    }

    /** Keeps task IDs index-aligned with every assistant message, including legacy messages. */
    private fun alignAssistantTaskIds() {
        val aligned = pl.aimasterpro.app.core.AssistantTaskIdAlignment.align(
            assistantTaskIds, messages.count { it.first == "assistant" }
        )
        assistantTaskIds.clear()
        assistantTaskIds.addAll(aligned)
    }

    @Synchronized private fun persistMessages() {
        if (currentChatId.isBlank()) currentChatId = UUID.randomUUID().toString()
        alignAssistantTaskIds()
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chats = r.optJSONObject("chats") ?: JSONObject()
        if (messages.isEmpty() && agentActivity.isEmpty()) { saveCurrentId(); return } // pusty nowy czat nie trafia na listę rozmów
        val arr = JSONArray()
        messages.forEach { (role, text) -> arr.put(JSONObject().put("role", role).put("text", text)) }
        val actArr = JSONArray()
        agentActivitySnapshot().forEach { actArr.put(it) }
        val taskIdArr = JSONArray()
        assistantTaskIds.forEach { taskIdArr.put(it) }
        val existing = chats.optJSONObject(currentChatId) ?: JSONObject()
        val placeholder = "Nowa rozmowa"
        val title = chatTitles[currentChatId]?.takeIf { it.isNotBlank() && it != placeholder }
            ?: existing.optString("title").takeIf { it.isNotBlank() && it != placeholder }
            ?: deriveTitle()
        chatTitles[currentChatId] = title
        chats.put(currentChatId, JSONObject()
            .put("title", title)
            .put("updatedAt", System.currentTimeMillis())
            .put("messages", arr)
            .put("agentActivity", actArr)
            .put("assistantTaskIds", taskIdArr)
        )
        r.put("currentId", currentChatId).put("chats", chats)
        chatPrefs.edit().putString("chats_v2", r.toString()).putString("chat_messages", arr.toString()).apply()
    }

    @Synchronized private fun persistAgentActivity() {
        if (currentChatId.isBlank()) return
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chats = r.optJSONObject("chats") ?: JSONObject()
        val chat = chats.optJSONObject(currentChatId) ?: JSONObject()
            .put("title", deriveTitle())
            .put("updatedAt", System.currentTimeMillis())
            .put("messages", JSONArray())
        val actArr = JSONArray()
        agentActivitySnapshot().forEach { actArr.put(it) }
        chat.put("agentActivity", actArr).put("updatedAt", System.currentTimeMillis())
        chats.put(currentChatId, chat)
        r.put("chats", chats)
        chatPrefs.edit().putString("chats_v2", r.toString()).apply()
    }

    /** Zapamiętuje tylko ID bieżącej rozmowy (bez zmiany kolejności listy). */
    private fun assistantChatId(): String =
        chatPrefs.getString("assistant_chat_id", "assistant_dedicated_chat_id")?.takeIf { it.isNotBlank() } ?: "assistant_dedicated_chat_id"

    private fun isAssistantChatId(id: String): Boolean =
        id == "assistant_dedicated_chat_id" || id.startsWith("assistant-")

    @Synchronized private fun saveCurrentId() {
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (!r.has("chats")) r.put("chats", JSONObject())
        r.put("currentId", currentChatId)
        val edit = chatPrefs.edit().putString("chats_v2", r.toString())
        if (currentChatId.isNotBlank() && !isAssistantChatId(currentChatId)) {
            edit.putString("last_user_chat_id", currentChatId)
        }
        edit.apply()
    }

    /** Dopisuje wiadomość do rozmowy zapisanej w pamięci, gdy użytkownik zdążył przejść do innej rozmowy. */
    @Synchronized private fun appendToStoredChat(id: String, role: String, text: String, taskId: String = "") {
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chat = r.optJSONObject("chats")?.optJSONObject(id) ?: return
        val arr = chat.optJSONArray("messages") ?: JSONArray()
        val storedIds = chat.optJSONArray("assistantTaskIds") ?: JSONArray()
        val existingAssistantCount = (0 until arr.length()).count { arr.optJSONObject(it)?.optString("role") == "assistant" }
        val ids = JSONArray(pl.aimasterpro.app.core.AssistantTaskIdAlignment.align(
            (0 until storedIds.length()).map { storedIds.optString(it) }, existingAssistantCount
        ))
        arr.put(JSONObject().put("role", role).put("text", text))
        if (role == "assistant" && taskId.isNotBlank()) {
            ids.put(taskId)
            chat.put("assistantTaskIds", ids)
        }
        chat.put("messages", arr).put("assistantTaskIds", ids).put("updatedAt", System.currentTimeMillis())
        chatPrefs.edit().putString("chats_v2", r.toString()).apply()
    }

    private fun restoreMessages() {
        messages.clear()
        assistantTaskIds.clear()
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chats = r.optJSONObject("chats") ?: JSONObject()
        for (id in chats.keys()) chatTitles[id] = chats.optJSONObject(id)?.optString("title", "") ?: ""
        currentChatId = r.optString("currentId", "")
        val currentChat = if (currentChatId.isNotBlank()) chats.optJSONObject(currentChatId) else null
        val arr = currentChat?.optJSONArray("messages")
        if (arr != null) {
            for (i in 0 until arr.length()) { val item = arr.getJSONObject(i); messages.add(item.optString("role") to item.optString("text")) }
            val ids = currentChat.optJSONArray("assistantTaskIds") ?: JSONArray()
            for (i in 0 until ids.length()) assistantTaskIds.add(ids.optString(i))
        } else if (chats.length() == 0) {
            val legacy = chatPrefs.getString("chat_messages", null)
            if (!legacy.isNullOrBlank()) runCatching {
                val a = JSONArray(legacy)
                for (i in 0 until a.length()) { val item = a.getJSONObject(i); messages.add(item.optString("role") to item.optString("text")) }
            }
        }
        if (currentChatId.isBlank() || (arr == null && messages.isEmpty())) currentChatId = UUID.randomUUID().toString()
        alignAssistantTaskIds()
        if (messages.isNotEmpty()) persistMessages()
        saveCurrentId()
    }

    @Synchronized private fun restoreMessagesForCurrentChat() {
        messages.clear()
        assistantTaskIds.clear()
        agentActivity.clear()
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chatObj = r.optJSONObject("chats")?.optJSONObject(currentChatId)
        val arr = chatObj?.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            messages.add(item.optString("role") to item.optString("text"))
        }
        val taskIds = chatObj?.optJSONArray("assistantTaskIds") ?: JSONArray()
        for (i in 0 until taskIds.length()) assistantTaskIds.add(taskIds.optString(i))
        alignAssistantTaskIds()
        val actArr = chatObj?.optJSONArray("agentActivity") ?: JSONArray()
        for (i in 0 until actArr.length()) {
            agentActivity.add(actArr.getString(i))
        }
    }

    @Synchronized private fun newAssistantChat() {
        if (!invalidateActiveEvidenceScopeForChatSwitch()) {
            toast("Nie można bezpiecznie wyczyścić aktywnego zakresu dowodów. Nie utworzono nowej rozmowy.")
            return
        }
        val newId = "assistant-${UUID.randomUUID()}"
        chatPrefs.edit().putString("assistant_chat_id", newId).apply()
        currentChatId = newId
        messages.clear()
        assistantTaskIds.clear()
        agentActivity.clear()
        attachedText = ""; attachedName = ""; attachedMime = ""; attachedBase64 = ""
        draft = ""
        persistMessages()
        navigate("Asystent")
    }

    @Synchronized private fun newChat() {
        if (!invalidateActiveEvidenceScopeForChatSwitch()) {
            toast("Nie można bezpiecznie wyczyścić aktywnego zakresu dowodów. Nie utworzono nowego czatu.")
            return
        }
        currentChatId = UUID.randomUUID().toString()
        messages.clear()
        assistantTaskIds.clear()
        agentActivity.clear()
        attachedText = ""; attachedName = ""; attachedMime = ""; attachedBase64 = ""
        draft = ""
        // navigate("Czat") restores last_user_chat_id, so update it before navigating.
        chatPrefs.edit().putString("last_user_chat_id", currentChatId).apply()
        saveCurrentId()
        navigate("Czat")
    }

    // =====================================================================
    //  Poniżej: funkcje bez zmian względem wersji 0.1.6 (załączniki, dostawcy, agenci, kopie)
    // =====================================================================

    private fun showAttachmentOptions() {
        val options = arrayOf("📄 Dokument / kod / tekst / PDF", "🖼 Obraz z urządzenia", "📚 Otwórz bibliotekę plików")
        AlertDialog.Builder(this).setTitle("Dodaj do wiadomości").setItems(options) { _, which ->
            when (which) {
                0 -> { val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }; startActivityForResult(i, PICK_ATTACHMENT) }
                1 -> { val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "image/*" }; startActivityForResult(i, PICK_ATTACHMENT) }
                2 -> showLibrary()
            }
        }.show()
    }

    private fun pinnedIds(): MutableSet<String> = chatPrefs.getStringSet("pinned_chats", emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun isPinned(id: String): Boolean = id.isNotBlank() && pinnedIds().contains(id)
    private fun togglePinned(id: String) { if (id.isBlank()) return; val set = pinnedIds(); if (!set.add(id)) set.remove(id); chatPrefs.edit().putStringSet("pinned_chats", set).apply() }

    private fun showLibrary() {
        val rootObj = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}").toString() }.getOrDefault("{}")
        val chats = runCatching { JSONObject(rootObj).optJSONObject("chats") ?: JSONObject() }.getOrDefault(JSONObject())
        val items = mutableListOf<String>()
        val it = chats.keys()
        while (it.hasNext()) { val o = chats.optJSONObject(it.next()) ?: continue; val arr = o.optJSONArray("messages") ?: continue; for (i in 0 until arr.length()) { val m = arr.optJSONObject(i) ?: continue; val t = m.optString("text"); if (t.contains("📎 Załącznik:")) items.add(t.substringAfter("📎 Załącznik:").trim().take(100)) } }
        val body = if (items.isEmpty()) "Biblioteka plików\n\nZałączniki są obecnie dostępne w rozmowie, w której je dodano. W tej wersji nie są kopiowane do osobnego magazynu aplikacji. Możesz dodać nowy plik przyciskiem poniżej." else "Ostatnio użyte załączniki\n\n" + items.distinct().take(30).joinToString("\n")
        AlertDialog.Builder(this).setTitle("Biblioteka").setMessage(body).setPositiveButton("＋ Dodaj plik") { _, _ -> showAttachmentOptions() }.setNegativeButton("Zamknij", null).show()
    }

    @Synchronized private fun agentActivitySnapshot(): List<String> = agentActivity.toList()

    @Synchronized private fun clearAgentActivityForCurrentChat() {
        agentActivity.clear()
        persistAgentActivity()
    }

    @Synchronized private fun appendAgentActivityForChat(chatId: String, entry: String) {
        if (chatId.isBlank()) return
        if (chatId == currentChatId) {
            agentActivity.add(entry)
            while (agentActivity.size > 150) agentActivity.removeAt(0)
            persistAgentActivity()
            if (screen == "Rozmowy" && collaborationChatId == chatId) {
                runOnUiThread { refreshCollaborationTimeline(chatId) }
            }
            return
        }
        val rootObj = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chat = rootObj.optJSONObject("chats")?.optJSONObject(chatId) ?: return
        val rows = chat.optJSONArray("agentActivity") ?: JSONArray()
        rows.put(entry)
        while (rows.length() > 150) rows.remove(0)
        chat.put("agentActivity", rows).put("updatedAt", System.currentTimeMillis())
        chatPrefs.edit().putString("chats_v2", rootObj.toString()).apply()
        if (screen == "Rozmowy" && collaborationChatId == chatId) {
            runOnUiThread { refreshCollaborationTimeline(chatId) }
        }
    }

    /** Evidence is selected only from the durable host-owned active ChangeSet pointer. */
    private fun buildMasterEvidenceContext(
        activeChatId: String
    ): pl.aimasterpro.app.core.MasterEvidenceContextBuilder.Result {
        val store = pl.aimasterpro.app.core.VerificationEvidenceStore(
            pl.aimasterpro.app.core.AndroidVerificationEvidenceBackend(applicationContext)
        )
        val resolver = pl.aimasterpro.app.core.VerificationEvidenceResolver(store)
        // The pending-receipt probe, workspace identity read, and active-pointer read must be
        // atomic with respect to every workspace mutation/recovery operation. A separate probe
        // followed by readActive() would permit a patch to start between those two operations.
        // Never wait on the UI path: lock contention means evidence is temporarily unavailable.
        val activeState = runCatching {
            pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.tryWithLock {
                val workspace = pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace"))
                val transactionState = runCatching { workspace.hasPendingWorkspaceTransaction() }
                    .fold(
                        onSuccess = { pending -> if (pending)
                            pl.aimasterpro.app.core.HostActiveEvidenceStartupGate.WorkspaceTransactionState.PENDING
                        else pl.aimasterpro.app.core.HostActiveEvidenceStartupGate.WorkspaceTransactionState.CLEAR },
                        onFailure = { pl.aimasterpro.app.core.HostActiveEvidenceStartupGate.WorkspaceTransactionState.UNKNOWN }
                    )
                pl.aimasterpro.app.core.HostActiveEvidenceStartupGate.resolve(transactionState) {
                    val workspaceIdentity = workspace.currentIdentity() ?: error("Nie można ustalić tożsamości workspace")
                    if (workspaceIdentity.isBlank()) error("Nie można ustalić tożsamości workspace")
                    pl.aimasterpro.app.core.HostActiveChangeSetStore.readActive(
                        applicationContext, activeChatId, workspaceIdentity
                    )
                }
            }
        }.getOrNull() ?: pl.aimasterpro.app.core.HostActiveChangeSetStore.ReadResult(
            pl.aimasterpro.app.core.HostActiveChangeSetStore.ReadStatus.INVALID,
            reason = "Workspace jest zajęty lub nie można bezpiecznie odczytać jego stanu; wybór dowodów odroczony"
        )
        val candidates = activeState.active?.let { active ->
            listOf(pl.aimasterpro.app.core.HostActiveEvidenceScopeResolver.Candidate(
                changeSet = pl.aimasterpro.app.core.MasterEvidenceContextBuilder.Candidate(
                    changeSetId = active.changeSetId,
                    taskId = active.taskId,
                    chatId = active.chatId,
                    snapshotId = active.snapshotId,
                    updatedAt = active.selectedAt
                ),
                status = active.changeStatus
            ))
        }.orEmpty()
        val hostScope = when (activeState.status) {
            pl.aimasterpro.app.core.HostActiveChangeSetStore.ReadStatus.ACTIVE ->
                pl.aimasterpro.app.core.HostActiveEvidenceScopeResolver.select(activeChatId, activeState.active?.changeSetId, candidates)
            pl.aimasterpro.app.core.HostActiveChangeSetStore.ReadStatus.NONE ->
                pl.aimasterpro.app.core.HostActiveEvidenceScopeResolver.select(activeChatId, null, candidates)
            pl.aimasterpro.app.core.HostActiveChangeSetStore.ReadStatus.INVALID ->
                pl.aimasterpro.app.core.HostActiveEvidenceScopeResolver.Result(
                    pl.aimasterpro.app.core.HostActiveEvidenceScopeResolver.Status.INVALID
                )
        }
        if (hostScope.status != pl.aimasterpro.app.core.HostActiveEvidenceScopeResolver.Status.SELECTED) {
            logAgentForChat(activeChatId, "🧾 Zakres dowodów hosta: ${hostScope.status} (${activeState.reason.ifBlank { activeState.status.name }}); bez automatycznego wyboru ostatniej zmiany.")
        }
        return pl.aimasterpro.app.core.MasterEvidenceContextBuilder.buildForHostScope(
            selected = hostScope.selected,
            activeChatId = activeChatId,
            resolver = resolver
        )
    }

    /**
     * Serialize chat switching against both patch activation and startup receipt recovery.
     * The Activity monitor serializes chat-id changes; the workspace gate serializes the
     * durable pointer invalidation with every patch/recovery commit. Lock order is Activity
     * monitor -> workspace gate; workspace-gated callbacks must remain lock-free with respect
     * to the Activity monitor to avoid inversion.
     */
    @Synchronized private fun invalidateActiveEvidenceScopeForChatSwitch(): Boolean = runCatching {
        pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
            pl.aimasterpro.app.core.HostActiveChangeSetStore.invalidate(applicationContext)
        }
    }.getOrDefault(false)

    /** Lock-free visibility check: callers may already hold the workspace lock. */
    private fun isCurrentChat(chatId: String): Boolean =
        chatId.isNotBlank() && currentChatId == chatId

    /**
     * Reconciles a patch committed by the workspace filesystem before Android killed the
     * process, but before MainActivity could persist its ChangeSet snapshot/status/pointer.
     * The receipt is acknowledged only after the ledger is durable and any eligible pointer
     * activation has succeeded. No patch is re-applied during recovery.
     */
    private fun recoverPendingPatchReceiptAtStartup() {
        Thread {
            val workspace = pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace"))
            val prepared = runCatching {
                pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                    val pending = workspace.readPendingPatchReceipt() ?: return@withLock null
                    val initial = pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.Receipt(
                        transactionId = pending.transactionId,
                        changeSetId = pending.changeSetId,
                        chatId = pending.chatId,
                        snapshotId = pending.snapshotId,
                        beforeWorkspaceIdentity = pending.beforeWorkspaceIdentity,
                        workspaceIdentity = pending.workspaceIdentity,
                        rolledBack = pending.rolledBack,
                        rollbackRequested = pending.rollbackRequested
                    )
                    val outcome = pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.reconcile(
                        initial,
                        object : pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.Backend {
                            override fun recoverRequestedRollback(
                                receipt: pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.Receipt
                            ) = workspace.recoverRequestedPatchRollback(receipt.changeSetId, receipt.transactionId).let {
                                receipt.copy(rolledBack = it.rolledBack, rollbackRequested = it.rollbackRequested)
                            }
                            override fun verifySnapshot(snapshotId: String) = workspace.verifySnapshot(snapshotId).valid
                            override fun snapshotIdentity(snapshotId: String) = workspace.snapshotIdentity(snapshotId)
                            override fun readLedger(changeSetId: String): pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.Ledger {
                                val ledger = pl.aimasterpro.app.core.ChangeSetStore.readResult(applicationContext)
                                if (ledger.status != pl.aimasterpro.app.core.ChangeSetStore.LedgerStatus.VALID) {
                                    return pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.Ledger(false, null)
                                }
                                val matches = ledger.items.filter { it.id == changeSetId }
                                if (matches.size != 1) return pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.Ledger(true, null)
                                val change = matches.single()
                                return pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.Ledger(
                                    true,
                                    pl.aimasterpro.app.core.PatchReceiptRecoveryCoordinator.LedgerEntry(
                                        change.chatId, change.status, change.snapshotId
                                    )
                                )
                            }
                            override fun currentWorkspaceIdentity() = workspace.currentIdentity()
                            override fun recordSnapshot(changeSetId: String, snapshotId: String) =
                                pl.aimasterpro.app.core.ChangeSetStore.recordSnapshot(applicationContext, changeSetId, snapshotId)
                            override fun transition(changeSetId: String, nextStatus: String) =
                                pl.aimasterpro.app.core.ChangeSetStore.transition(applicationContext, changeSetId, nextStatus)
                            override fun invalidateActiveScope(changeSetId: String) =
                                pl.aimasterpro.app.core.HostActiveChangeSetStore.invalidate(applicationContext, changeSetId)
                        }
                    )
                    outcome.receipt to (outcome.eligibleForActivation to outcome.effectiveRolledBack)
                }
            }
            if (prepared.isFailure) {
                val message = "⚠️ Odzyskiwanie transakcji patcha zostało zablokowane: ${prepared.exceptionOrNull()?.message ?: "błąd"}. Potwierdzenie zachowano; nie powtarzaj patcha ani nie usuwaj plików workspace przed kontrolą."
                android.util.Log.e("AIMasterPatchRecovery", message, prepared.exceptionOrNull())
                val chatId = currentChatId
                if (chatId.isNotBlank()) logAgentForChat(chatId, message)
                return@Thread
            }
            val value = prepared.getOrNull() ?: return@Thread
            val (receipt, activationState) = value
            val (eligibleForActivation, effectiveRolledBack) = activationState
            val commitResult = runCatching {
                pl.aimasterpro.app.core.PatchReceiptRecoveryCommitGate.commit(
                    eligibleForActivation = eligibleForActivation,
                    expectedWorkspaceIdentity = receipt.workspaceIdentity,
                    chatStillMatches = { isCurrentChat(receipt.chatId) },
                    currentWorkspaceIdentity = { workspace.currentIdentity() ?: "" },
                    activate = {
                        pl.aimasterpro.app.core.HostActiveChangeSetStore.setActive(
                            applicationContext, receipt.chatId, receipt.workspaceIdentity, receipt.changeSetId
                        )
                    },
                    invalidateActiveScope = {
                        check(pl.aimasterpro.app.core.HostActiveChangeSetStore.invalidate(applicationContext, receipt.changeSetId)) {
                            "Nie udało się unieważnić zakresu po zmianie czatu/workspace."
                        }
                    },
                    acknowledge = { workspace.acknowledgePatchReceipt(receipt.changeSetId, receipt.transactionId) }
                )
            }.getOrElse {
                android.util.Log.e("AIMasterPatchRecovery", "Commit recovery receipt failed", it)
                pl.aimasterpro.app.core.PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGE_FAILED
            }
            if (commitResult == pl.aimasterpro.app.core.PatchReceiptRecoveryCommitGate.Result.ACTIVATION_FAILED) {
                val message = "⚠️ Nie udało się odtworzyć aktywnego zakresu dowodów; potwierdzenie patcha pozostawiono do ponowienia."
                android.util.Log.e("AIMasterPatchRecovery", message)
                logAgentForChat(receipt.chatId, message)
                return@Thread
            }
            if (commitResult == pl.aimasterpro.app.core.PatchReceiptRecoveryCommitGate.Result.INVALIDATION_FAILED) {
                val message = "⚠️ Nie udało się trwale unieważnić aktywnego zakresu po zmianie czatu/workspace; receipt pozostawiono do bezpiecznego ponowienia."
                android.util.Log.e("AIMasterPatchRecovery", message)
                logAgentForChat(receipt.chatId, message)
                return@Thread
            }
            if (commitResult == pl.aimasterpro.app.core.PatchReceiptRecoveryCommitGate.Result.ACKNOWLEDGE_FAILED) {
                val message = "⚠️ Historia patcha została uzgodniona, ale potwierdzenie transakcji nie zostało usunięte. Zostanie ponowione przy kolejnym uruchomieniu."
                android.util.Log.e("AIMasterPatchRecovery", message)
                logAgentForChat(receipt.chatId, message)
                return@Thread
            }
            // Keep the post-commit diagnostic read atomic with workspace identity. This does
            // not decide whether commit succeeded; it only prevents a concurrent import/patch
            // from making the recovery log describe a mixed workspace/pointer observation.
            // This runs on the recovery worker, not the UI thread, so it may wait for the lock.
            // null means the observation itself failed; do not misreport that as a scope mismatch.
            val activated: Boolean? = if (!eligibleForActivation || !isCurrentChat(receipt.chatId)) false
            else runCatching {
                pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                    val identity = workspace.currentIdentity()
                    identity == receipt.workspaceIdentity &&
                        pl.aimasterpro.app.core.HostActiveChangeSetStore.readActive(
                            applicationContext, receipt.chatId, receipt.workspaceIdentity
                        ).let { it.status == pl.aimasterpro.app.core.HostActiveChangeSetStore.ReadStatus.ACTIVE && it.active?.changeSetId == receipt.changeSetId }
                }
            }.getOrNull()
            logAgentForChat(receipt.chatId, when {
                effectiveRolledBack -> "↩️ Odzyskano po restarcie przerwany/wycofany patch ${receipt.changeSetId}; poprzedni workspace pozostał aktywny; rejestr uzgodniono jako REJECTED/ROLLED_BACK."
                activated == true -> "♻️ Odzyskano po restarcie patch ${receipt.changeSetId}; snapshot ${receipt.snapshotId}; status APPLIED_UNVERIFIED; aktywny zakres przywrócony."
                activated == null -> "♻️ Historia patcha ${receipt.changeSetId} została uzgodniona, ale nie udało się ponownie odczytać aktywnego zakresu; stan aktywacji pozostaje niepotwierdzony."
                else -> "♻️ Odzyskano zapis patcha ${receipt.changeSetId}; snapshot ${receipt.snapshotId}; historia uzgodniona. Zakres dowodów nie został aktywowany, ponieważ czat lub workspace nie jest już tym samym zakresem."
            })
        }.apply { name = "PatchApplyRecovery" }.start()
    }

    /** A patch may become active only if its originating chat is still the selected chat. */
    @Synchronized private fun activateChangeSetForCurrentChat(
        chatId: String,
        changeSetId: String,
        expectedWorkspaceIdentity: String
    ): Boolean {
        if (chatId.isBlank() || currentChatId != chatId || expectedWorkspaceIdentity.isBlank()) return false
        return pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
            val currentIdentity = currentWorkspaceIdentity()
            currentIdentity == expectedWorkspaceIdentity && pl.aimasterpro.app.core.HostActiveChangeSetStore.setActive(
                applicationContext, chatId, expectedWorkspaceIdentity, changeSetId
            )
        }
    }

    /** Stable identity changes whenever the imported workspace manifest is replaced. */
    private fun currentWorkspaceIdentity(): String {
        return pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.tryWithLock {
            runCatching {
                pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace")).currentIdentity()
            }.getOrNull().orEmpty()
        }.orEmpty()
    }

    private fun logAgentForChat(chatId: String, line: String) {
        val stamp = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        appendAgentActivityForChat(chatId, "$stamp — $line")
    }

    private fun logAgent(line: String) = logAgentForChat(currentChatId, line)

    private fun logAgentDialogueForChat(chatId: String, sender: String, recipient: String, message: String, status: String = "INFO") {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val sanitized = pl.aimasterpro.app.core.CommunicationLogSanitizer.sanitize(message)
        appendAgentActivityForChat(chatId, "[$stamp] $sender ➔ $recipient [$status]:\n$sanitized")
    }

    private fun logAgentDialogue(sender: String, recipient: String, message: String, status: String = "INFO") =
        logAgentDialogueForChat(currentChatId, sender, recipient, message, status)

    private fun showAgentCollaborationViewer() {
        loadAgentActivity()
        val activityRows = agentActivitySnapshot()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
        val title = TextView(this).apply { text = "Wymiana danych między agentami"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white) }
        box.addView(title)
        val sub = TextView(this).apply { text = "Dialogi i raporty powiązane wyłącznie z bieżącą rozmową."; textSize = 12f; setTextColor(muted); setPadding(0, dp(4), 0, dp(12)) }
        box.addView(sub)

        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (activityRows.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "Brak zarejestrowanych rozmów agentów w tej rozmowie.\nWyślij zapytanie w trybie MASTER, aby uruchomić podagentów."
                textSize = 14f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
            }
            listLayout.addView(emptyTv)
        } else {
            agentActivitySnapshot().asReversed().forEach { entry ->
                val cardView = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(cardBg, 12, border)
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.bottomMargin = dp(10)
                    layoutParams = lp
                }
                val isError = entry.contains("ERROR") || entry.contains("FAILED") || entry.contains("TOKEN_LIMIT")
                val isFailover = entry.contains("FAILOVER")
                val isRequest = entry.contains("REQUEST")
                val textColor = when {
                    isError -> Color.rgb(255, 100, 100)
                    isFailover -> orange
                    isRequest -> cyan
                    else -> white
                }
                val tv = TextView(this).apply {
                    text = entry
                    textSize = 13f
                    setTextColor(textColor)
                    typeface = Typeface.MONOSPACE
                    setLineSpacing(dp(2).toFloat(), 1f)
                    setTextIsSelectable(true)
                }
                cardView.addView(tv)
                listLayout.addView(cardView)
            }
        }

        val scroll = ScrollView(this).apply {
            addView(listLayout)
            val lp = LinearLayout.LayoutParams(-1, dp(420))
            layoutParams = lp
        }
        box.addView(scroll)

        val dialog = AlertDialog.Builder(this).setView(box).create()
        dialog.window?.setBackgroundDrawable(rounded(panel, 20))
        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "Zamknij") { d, _ -> d.dismiss() }
        dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Kopiuj") { _, _ -> copyText(agentActivitySnapshot().joinToString("\n\n")) }
        dialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Wyczyść") { _, _ ->
            clearAgentActivityForCurrentChat()
            dialog.dismiss()
            toast("Wyczyszczono historię dla tej rozmowy.")
            navigate("Czat")
        }
        dialog.show()
    }

    private fun showAgentCollaborationScreen() {
        loadAgentActivity()
        val col = content
        col.setPadding(dp(16), dp(14), dp(16), dp(24))

        card("Komunikacja i Dialogi Agentów", "Rzeczywisty dziennik żądań wysłanych do modeli, odpowiedzi agentów, błędów i syntezy MASTER-a. Nowe zdarzenia pojawiają się automatycznie podczas pracy zespołu.")

        val actionsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(12)) }
        val copyBtn = TextView(this).apply {
            text = "📋 Kopiuj wszystkie rozmowy"
            textSize = 14f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ripple(panel, 12)
            setOnClickListener { copyText(agentActivitySnapshot().joinToString("\n\n")); toast("Skopiowano historię rozmów agentów.") }
        }
        actionsRow.addView(copyBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })

        val clearBtn = TextView(this).apply {
            text = "🗑 Wyczyść"
            textSize = 14f
            setTextColor(Color.rgb(255, 100, 100))
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = ripple(panel, 12)
            setOnClickListener {
                clearAgentActivityForCurrentChat()
                collaborationRenderedEntries.clear()
                collaborationListLayout?.removeAllViews()
                navigate("Rozmowy")
                toast("Wyczyszczono historię rozmów dla tej rozmowy.")
            }
        }
        actionsRow.addView(clearBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
        col.addView(actionsRow)

        val liveLabel = TextView(this).apply {
            text = "● DZIENNIK NA ŻYWO  •  zapisywany w historii tej rozmowy"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(cyan)
            setPadding(dp(2), dp(4), 0, dp(8))
        }
        col.addView(liveLabel)
        val summary = TextView(this).apply {
            textSize = 11f
            setTextColor(muted)
            setPadding(dp(2), 0, 0, dp(10))
        }
        collaborationSummaryText = summary
        col.addView(summary)

        val timeline = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = false
            addView(timeline)
            // This screen is already hosted inside the app's outer ScrollView;
            // give the inner live timeline a concrete viewport instead of weight=1.
            layoutParams = LinearLayout.LayoutParams(-1, dp(560))
        }
        col.addView(scroll)
        collaborationListLayout = timeline
        collaborationScrollView = scroll
        collaborationChatId = currentChatId
        collaborationRenderedEntries.clear()
        refreshCollaborationTimeline(currentChatId, scrollToEnd = true)
    }

    private fun createAgentActivityCard(entry: String): View {
        val isError = entry.contains("ERROR") || entry.contains("FAILED") || entry.contains("TOKEN_LIMIT") || entry.contains("NETWORK_ERROR")
        val isFailover = entry.contains("FAILOVER")
        val isRequest = entry.contains("REQUEST")
        val isReport = entry.contains("REPORT")
        val accentColor = when {
            isError -> Color.rgb(255, 100, 100)
            isFailover -> orange
            isRequest -> cyan
            isReport -> Color.rgb(120, 220, 150)
            else -> white
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(cardBg, 14, border)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
            val badge = TextView(this@MainActivity).apply {
                text = when {
                    isRequest -> "📤 ŻĄDANIE WYSŁANE DO MODELU"
                    isReport -> "📥 PEŁNA ODPOWIEDŹ AGENTA"
                    entry.contains("AGGREGATION") -> "🧠 SYNTEZA MASTER-A"
                    isFailover -> "🔄 FAILOVER / ROUTING"
                    isError -> "❌ BŁĄD / LIMIT"
                    else -> "ℹ️ ZDARZENIE ZESPOŁU"
                }
                textSize = 10.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(accentColor)
                setPadding(0, 0, 0, dp(5))
            }
            addView(badge)
            val body = TextView(this@MainActivity).apply {
                text = if (entry.length > 900) entry.take(900).trimEnd() + "\n\n… pełna treść dostępna po rozwinięciu" else entry
                textSize = 12.5f
                setTextColor(white)
                typeface = Typeface.MONOSPACE
                setLineSpacing(dp(3).toFloat(), 1f)
                setTextIsSelectable(true)
            }
            addView(body)
            if (entry.length > 900) {
                val expand = TextView(this@MainActivity).apply {
                    text = "Pokaż pełną treść ↓"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(cyan)
                    setPadding(0, dp(9), 0, 0)
                    setOnClickListener {
                        val expanded = body.tag == true
                        body.tag = !expanded
                        body.text = if (expanded) entry.take(900).trimEnd() + "\n\n… pełna treść dostępna po rozwinięciu" else entry
                        text = if (expanded) "Pokaż pełną treść ↓" else "Zwiń treść ↑"
                    }
                }
                addView(expand)
            }
        }
    }

    /** Appends only unseen events; rebuilds safely if the bounded history was cleared/rotated. */
    private fun refreshCollaborationTimeline(chatId: String, scrollToEnd: Boolean = true) {
        if (screen != "Rozmowy" || chatId.isBlank() || chatId != currentChatId || chatId != collaborationChatId) return
        val layout = collaborationListLayout ?: return
        val rows = agentActivitySnapshot()
        val requestCount = rows.count { it.contains("[REQUEST]") }
        val reportCount = rows.count { it.contains("[REPORT]") }
        val reviewCount = rows.count { it.contains("PEER_REVIEW") || it.contains("Peer Review") }
        val errorCount = rows.count { it.contains("[ERROR]") || it.contains("[FAILED]") || it.contains("[TOKEN_LIMIT]") || it.contains("[NETWORK_ERROR]") }
        collaborationSummaryText?.text = "Zdarzenia: ${rows.size}/150  •  żądania: $requestCount  •  odpowiedzi: $reportCount  •  recenzje: $reviewCount  •  błędy: $errorCount"
        val prefixStillMatches = collaborationRenderedEntries.size <= rows.size &&
            collaborationRenderedEntries.indices.all { collaborationRenderedEntries[it] == rows[it] }
        if (!prefixStillMatches) {
            layout.removeAllViews()
            collaborationRenderedEntries.clear()
        }
        if (rows.isEmpty()) {
            if (layout.childCount == 0) layout.addView(TextView(this).apply {
                text = "Brak zdarzeń dla tej rozmowy. Wybierz tryb MASTER i wyślij zadanie, aby zobaczyć rzeczywiste zlecenia oraz odpowiedzi agentów."
                textSize = 14f
                setTextColor(muted)
                gravity = Gravity.CENTER
                setPadding(dp(20), dp(32), dp(20), dp(32))
            })
            collaborationRenderedEntries.clear()
            return
        }
        // Remove the empty-state placeholder before appending the first event.
        if (layout.childCount == 1 && collaborationRenderedEntries.isEmpty() && rows.isNotEmpty()) layout.removeAllViews()
        for (index in collaborationRenderedEntries.size until rows.size) {
            layout.addView(createAgentActivityCard(rows[index]))
            collaborationRenderedEntries.add(rows[index])
        }
        if (scrollToEnd) collaborationScrollView?.post { collaborationScrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    @Synchronized private fun loadAgentActivity() {
        agentActivity.clear()
        if (currentChatId.isBlank()) return
        val r = runCatching { JSONObject(chatPrefs.getString("chats_v2", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val chatObj = r.optJSONObject("chats")?.optJSONObject(currentChatId) ?: return
        val arr = chatObj.optJSONArray("agentActivity") ?: return
        for (i in 0 until arr.length()) { agentActivity.add(arr.getString(i)) }
    }

    private fun chooseAttachment() = showAttachmentOptions()

    /** Adds ready-to-configure provider profiles once; API keys are intentionally blank. */
    private fun seedDefaultProviders() {
        val existingIds = store.allConfigs().map { it.optString("id") }.toSet()
        val defaults = listOf(
            ProviderConfig("gemini", "Google Gemini API", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "", "", true),
            ProviderConfig("groq", "Groq API", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "", "", true),
            ProviderConfig("openrouter", "OpenRouter API", ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "", "", true),
            ProviderConfig("anthropic", "Anthropic API", ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1", "", "", true),
            ProviderConfig("openai", "OpenAI API", ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "", "", true),
            ProviderConfig("mistral", "Mistral AI", ProviderKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1", "", "", true),
            ProviderConfig("ollama", "Ollama • modele lokalne", ProviderKind.OPENAI_COMPATIBLE, "http://10.0.2.2:11434/v1", "", "", false),
            ProviderConfig("custom", "Własny endpoint (OpenAI-compatible)", ProviderKind.OPENAI_COMPATIBLE, "", "", "", false)
        )
        defaults.filter { it.id !in existingIds }.forEach { saveProvider(it) }
    }

    private fun showProviders() {
        val allSaved = store.allConfigs().mapNotNull { j -> runCatching { loadProvider(j.optString("id")) }.getOrNull() }
        val baseProfiles = allSaved.filter { it.parentProviderId.isBlank() }

        card("MODELE • centrum zarządzania", "Jedno miejsce do pobierania modeli, sprawdzania cen, kopiowania identyfikatorów i tworzenia profili modeli. Agenci korzystają później z gotowych profili.")

        val syncCard = card("Katalog modeli", "Dane pochodzą z API skonfigurowanych dostawców. Cena nieznana nie jest traktowana jako darmowa.")
        action("⟳ ODŚWIEŻ WSZYSTKICH DOSTAWCÓW", syncCard) { refreshAllModelCatalogs(showToast = true) }
        action("＋ Dodaj dostawcę / endpoint", syncCard) {
            providerDialog(ProviderConfig("custom_" + System.currentTimeMillis(), "Własny endpoint", ProviderKind.OPENAI_COMPATIBLE, "", "", ""))
        }

        val search = field("Szukaj modelu, dostawcy lub ID…")
        syncCard.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        val filterSpinner = Spinner(this)
        filterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Wszystkie modele", "Darmowe / lokalne", "Płatne", "Nieznana cena"))
        syncCard.addView(filterSpinner, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(4) })
        val modelsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        syncCard.addView(modelsContainer, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        fun renderCatalog(query: String, filter: Int) {
            modelsContainer.removeAllViews()
            val entries = readModelCatalogEntries().filter { entry ->
                val hay = "${entry.providerName} ${entry.info.name} ${entry.info.id}".lowercase()
                val matchesQuery = query.isBlank() || hay.contains(query.lowercase())
                val local = entry.providerId.contains("ollama", true) || entry.baseUrl.contains("11434")
                val matchesFilter = when (filter) {
                    1 -> local || entry.info.freeByPricing == true
                    2 -> entry.info.freeByPricing == false
                    3 -> !local && entry.info.freeByPricing == null
                    else -> true
                }
                matchesQuery && matchesFilter
            }.sortedBy { entry ->
                val local = entry.providerId.contains("ollama", true) || entry.baseUrl.contains("11434")
                when { local || entry.info.freeByPricing == true -> 0; entry.info.freeByPricing == null -> 1; else -> 2 }
            }
            if (entries.isEmpty()) {
                modelsContainer.addView(TextView(this).apply {
                    text = "Katalog jest pusty.\n\n1. Dodaj klucz API w profilu dostawcy.\n2. Kliknij ODŚWIEŻ WSZYSTKICH DOSTAWCÓW.\n3. Wybierz model i utwórz dla niego profil.\n\nLista nie korzysta ze starych, wpisanych na stałe nazw modeli."
                    setTextColor(muted); textSize = 13f; setPadding(0, dp(12), 0, dp(12))
                })
                return
            }
            val freeCount = entries.count { it.info.freeByPricing == true || it.providerId.contains("ollama", true) || it.baseUrl.contains("11434") }
            val paidCount = entries.count { it.info.freeByPricing == false }
            val unknownCount = entries.size - freeCount - paidCount
            modelsContainer.addView(TextView(this).apply {
                text = "Wyniki: ${entries.size} • darmowe/lokalne: $freeCount • płatne: $paidCount • nieznana cena: $unknownCount\nOstatnia synchronizacja: ${modelCatalogPrefs.getLong("last_sync", 0L).takeIf { it > 0L }?.let { java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "brak"}"
                setTextColor(cyan); textSize = 11f; setPadding(0, 0, 0, dp(8))
            })
            entries.take(80).forEach { entry ->
                val info = entry.info
                val isLocal = entry.providerId.contains("ollama", true) || entry.baseUrl.contains("11434")
                val freeLabel = when {
                    isLocal -> "LOKALNY • BEZ OPŁAT API"
                    info.freeByPricing == true && info.promptPricePerMillion != null && info.completionPricePerMillion != null -> "FREE • CENA API = 0 (limity konta mogą obowiązywać)"
                    info.freeByPricing == true -> "FREE • JAWNE OZNACZENIE DOSTAWCY"
                    info.freeByPricing == false -> "PŁATNY"
                    else -> "CENA NIEZNANA"
                }
                val freeColor = when {
                    isLocal || info.freeByPricing == true -> Color.rgb(65, 205, 120)
                    info.freeByPricing == false -> Color.rgb(255, 125, 95)
                    else -> orange
                }
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(cardBg, 12, border)
                    val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8); layoutParams = lp
                }
                row.addView(TextView(this).apply { text = entry.providerName; textSize = 11f; setTextColor(cyan); typeface = Typeface.DEFAULT_BOLD })
                row.addView(TextView(this).apply { text = info.name; textSize = 14f; setTextColor(white); typeface = Typeface.DEFAULT_BOLD; setPadding(0, dp(3), 0, 0) })
                row.addView(TextView(this).apply { text = info.id; textSize = 11f; setTextColor(muted); setTextIsSelectable(true); setPadding(0, dp(2), 0, dp(3)) })
                val priceText = when {
                    isLocal -> "Lokalny model — bez opłat za API; koszty zależą od własnego sprzętu/prądu."
                    info.promptPricePerMillion != null && info.completionPricePerMillion != null -> "Wejście: \$${"%.4f".format(info.promptPricePerMillion)} / 1M • Wyjście: \$${"%.4f".format(info.completionPricePerMillion)} / 1M tokenów (USD)"
                    else -> "Dostawca nie zwrócił danych cenowych w katalogu modeli."
                }
                val contextText = info.contextLength?.let { " • kontekst: ${it} tokenów" }.orEmpty()
                row.addView(TextView(this).apply { text = "$freeLabel\n$priceText$contextText\nWykryto na liście dostawcy; test odpowiedzi wykonaj przyciskiem TESTUJ."; textSize = 11f; setTextColor(freeColor); setPadding(0, 0, 0, dp(5)) })
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                fun smallButton(label: String, click: () -> Unit) {
                    val b = TextView(this@MainActivity).apply {
                        text = label; textSize = 11f; setTextColor(white); gravity = Gravity.CENTER
                        setPadding(dp(10), dp(8), dp(10), dp(8)); background = ripple(panel, 9)
                        setOnClickListener { click() }
                    }
                    buttons.addView(b, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(5) })
                }
                smallButton("KOPIUJ ID") { copyText(info.id) }
                smallButton("DODAJ PROFIL") {
                    val provider = loadProvider(entry.providerId)
                    if (provider == null) toast("Profil dostawcy już nie istnieje. Odśwież katalog.")
                    else createModelProfile(provider, info)
                }
                smallButton("TESTUJ") {
                    val provider = loadProvider(entry.providerId)
                    if (provider == null) toast("Brak profilu dostawcy.")
                    else testSpecificModel(provider, info)
                }
                row.addView(buttons)
                modelsContainer.addView(row)
            }
            if (entries.size > 80) modelsContainer.addView(TextView(this).apply { text = "Pokazano pierwsze 80 wyników. Użyj wyszukiwarki lub filtra, aby zawęzić listę."; setTextColor(muted); textSize = 11f })
        }
        renderCatalog("", 0)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { renderCatalog(s?.toString().orEmpty(), filterSpinner.selectedItemPosition) }
        })
        filterSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                renderCatalog(search.text.toString(), position)
            }
        }

        val providersCard = card("Dostawcy i klucze API", "Klucze są szyfrowane przez Android Keystore. Profil dostawcy przechowuje połączenie; profile modeli powstają z listy powyżej.")
        baseProfiles.forEach { p ->
            val usage = TokenTracker.getUsage(this, p.id)
            val hasKey = p.apiKey.isNotBlank() || p.id.equals("ollama", true) || p.baseUrl.contains("11434")
            val statusText = if (!hasKey) "Brak klucza API" else if (p.baseUrl.isBlank()) "Uzupełnij Base URL" else "Dane dostawcy zapisane"
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(cardBg, 12, border)
                val lp = LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8); layoutParams = lp
            }
            box.addView(TextView(this).apply { text = p.name; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white) })
            box.addView(TextView(this).apply { text = "${p.baseUrl}\nModel bazowy: ${p.model.ifBlank { "nie wybrano — utwórz profil z katalogu" }}\n$statusText • ${usage.totalTokens} tokenów zarejestrowanych"; textSize = 11f; setTextColor(muted); setPadding(0, dp(3), 0, dp(6)) })
            fun providerButton(label: String, click: () -> Unit) {
                val b = TextView(this).apply { text = label; textSize = 12f; gravity = Gravity.CENTER; setTextColor(white); setPadding(dp(10), dp(9), dp(10), dp(9)); background = ripple(panel, 9); setOnClickListener { click() } }
                box.addView(b, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5) })
            }
            providerButton("POBIERZ / ODŚWIEŻ MODELE") { discover(p) }
            providerButton("TESTUJ AKTUALNY MODEL") { if (p.model.isBlank()) toast("Najpierw wybierz model w katalogu.") else testProvider(p) }
            providerButton("EDYTUJ DOSTAWCĘ / KLUCZ") { providerDialog(p) }
            providerButton("RESETUJ LICZNIK TOKENÓW") { TokenTracker.resetUsage(this, p.id); showShell("Modele"); showProviders() }
            providersCard.addView(box)
        }
        action("Wczytaj bazę / kopię zapasową", providersCard) { chooseBackupToImport() }
        action("Eksportuj zaszyfrowaną bazę modeli i kluczy", providersCard) { promptBackupPassword(export = true) }

        val savedModelProfiles = allSaved.filter { it.parentProviderId.isNotBlank() }
        if (savedModelProfiles.isNotEmpty()) {
            val profilesCard = card("ZAPISANE PROFILE MODELI", "Każdy profil ma własny identyfikator modelu, ale korzysta z klucza API profilu bazowego. Dzięki temu różni agenci mogą używać różnych modeli tego samego dostawcy.")
            savedModelProfiles.forEach { profile ->
                val selected = selectedProviderId == profile.id
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(9), dp(10), dp(9)); background = rounded(cardBg, 10, border) }
                row.addView(TextView(this).apply { text = (if (selected) "✓ AKTYWNY • " else "") + profile.name; setTextColor(if (selected) orange else white); textSize = 13f; typeface = Typeface.DEFAULT_BOLD })
                row.addView(TextView(this).apply { text = profile.model; setTextColor(muted); textSize = 11f; setPadding(0, dp(2), 0, dp(4)); setTextIsSelectable(true) })
                action("Ustaw jako model czatu", row) {
                    selectedProviderId = profile.id
                    agentProviders[AgentRole.MASTER.name] = profile.id
                    chatPrefs.edit().putString("selected_provider", profile.id)
                        .putString("agent_provider_${AgentRole.MASTER.name}", profile.id).apply()
                    showShell("Modele"); showProviders(); toast("Aktywny model czatu: ${profile.model}")
                }
                action("Kopiuj ID modelu", row) { copyText(profile.model) }
                action("Testuj ten profil", row) { testProvider(profile) }
                profilesCard.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            }
        }

        // Synchronizacja automatyczna najwyżej raz na 6 godzin; ręczne odświeżenie działa zawsze.
        val lastAttempt = modelCatalogPrefs.getLong("last_attempt", 0L)
        if (lastAttempt == 0L || System.currentTimeMillis() - lastAttempt > 6 * 60 * 60 * 1000L) {
            refreshAllModelCatalogs(showToast = false)
        }
    }

    private data class CatalogEntry(
        val providerId: String,
        val providerName: String,
        val baseUrl: String,
        val info: ProviderModelInfo
    )

    private fun readModelCatalogEntries(): List<CatalogEntry> {
        val raw = modelCatalogPrefs.getString("models_json", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        val out = mutableListOf<CatalogEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("modelId"); val providerId = o.optString("providerId")
            if (id.isBlank() || providerId.isBlank()) continue
            val prompt = if (o.isNull("promptPricePerMillion")) null else o.optDouble("promptPricePerMillion", Double.NaN).takeIf { it.isFinite() }
            val completion = if (o.isNull("completionPricePerMillion")) null else o.optDouble("completionPricePerMillion", Double.NaN).takeIf { it.isFinite() }
            val free = if (o.isNull("freeByPricing")) null else o.optBoolean("freeByPricing")
            out.add(CatalogEntry(providerId, o.optString("providerName", providerId), o.optString("baseUrl"), ProviderModelInfo(
                id, o.optString("modelName", id), o.optLong("contextLength", -1L).takeIf { it > 0 }, prompt, completion, free, o.optString("description")
            )))
        }
        return out.distinctBy { "${it.providerId}:${it.info.id}" }.sortedWith(compareBy({ it.providerName.lowercase() }, { it.info.id.lowercase() }))
    }

    private fun saveModelCatalogForProvider(provider: ProviderConfig, models: List<ProviderModelInfo>) {
        val current = readModelCatalogEntries().filterNot { it.providerId == provider.id }
        val combined = current + models.map { CatalogEntry(provider.id, provider.name, provider.baseUrl, it) }
        val arr = JSONArray()
        combined.forEach { e ->
            arr.put(JSONObject().put("providerId", e.providerId).put("providerName", e.providerName).put("baseUrl", e.baseUrl)
                .put("modelId", e.info.id).put("modelName", e.info.name).put("contextLength", e.info.contextLength ?: JSONObject.NULL)
                .put("promptPricePerMillion", e.info.promptPricePerMillion ?: JSONObject.NULL)
                .put("completionPricePerMillion", e.info.completionPricePerMillion ?: JSONObject.NULL)
                .put("freeByPricing", e.info.freeByPricing ?: JSONObject.NULL).put("description", e.info.description))
        }
        modelCatalogPrefs.edit().putString("models_json", arr.toString()).apply()

    }


    private fun providerCredentialsReady(config: ProviderConfig): Boolean {
        if (config.baseUrl.isBlank()) return false
        val local = config.id.equals("ollama", true) || config.baseUrl.contains("11434") || config.baseUrl.contains("localhost") || config.baseUrl.contains("127.0.0.1")
        return local || config.apiKey.isNotBlank()
    }

    private fun refreshAllModelCatalogs(showToast: Boolean) {
        val providers = store.allConfigs().mapNotNull { j -> runCatching { loadProvider(j.optString("id")) }.getOrNull() }
            .filter { it.parentProviderId.isBlank() && providerCredentialsReady(it) }
        if (providers.isEmpty()) {
            if (showToast) toast("Brak skonfigurowanych dostawców. Dodaj Base URL i klucz API.")
            return
        }
        status.text = "Pobieranie katalogów modeli…"
        executor.execute {
            var success = 0
            val failures = mutableListOf<String>()
            providers.forEach { provider ->
                val result = runCatching { client.discoverModelDetails(provider) }
                if (result.isSuccess) {
                    val discovered = result.getOrDefault(emptyList())
                    if (discovered.isNotEmpty()) {
                        saveModelCatalogForProvider(provider, discovered)
                        success++
                    } else failures.add("${provider.name}: API zwróciło pustą listę; zachowano poprzedni katalog")
                } else failures.add("${provider.name}: ${result.exceptionOrNull()?.message ?: "błąd"}")
            }
            runOnUiThread {
                val now = System.currentTimeMillis()
                val editor = modelCatalogPrefs.edit().putLong("last_attempt", now)
                if (success > 0) editor.putLong("last_sync", now)
                editor.apply()
                status.text = "Katalog modeli: $success/${providers.size} dostawców"
                showShell("Modele"); showProviders()
                if (showToast) toast(if (success > 0) "Pobrano modele od $success/${providers.size} dostawców." else "Nie udało się pobrać katalogów. Sprawdź klucze i adresy API.")
                if (success == 0 && failures.isNotEmpty()) {
                    AlertDialog.Builder(this).setTitle("Nie udało się pobrać katalogu")
                        .setMessage(failures.joinToString("\n\n").take(5000)).setPositiveButton("OK", null).show()
                }
            }
        }
    }

    private fun createModelProfile(provider: ProviderConfig, info: ProviderModelInfo) {
        val baseId = provider.parentProviderId.ifBlank { provider.id }
        val modelUuid = UUID.nameUUIDFromBytes("${baseId}:${info.id}".toByteArray(Charsets.UTF_8)).toString().replace("-", "")
        val profileId = "${baseId}__model_${modelUuid}"
        val existing = loadProvider(profileId)
        val profile = existing ?: provider.copy(
            id = profileId,
            name = "${provider.name} • ${info.name}",
            model = info.id,
            apiKey = "", // klucz pozostaje wyłącznie w profilu bazowym dostawcy
            parentProviderId = baseId
        )
        saveProvider(profile.copy(apiKey = ""))
        showShell("Modele"); showProviders()
        toast("Dodano profil modelu: ${info.name}. Przypisz go odpowiedniej roli w zakładce Agenci.")
    }

    private fun recordModelTestUsage(
        config: ProviderConfig,
        response: ChatResponse?,
        testLabel: String,
        taskId: String,
        reservationId: String,
        error: Throwable? = null
    ) {
        // Provider health telemetry must not prevent accounting after a billable response.
        runCatching {
            pl.aimasterpro.app.core.ModelHealthTracker.record(
                this, config, response != null,
                if (response != null) "" else if ((error as? ProviderException)?.httpCode == 429) "HTTP_429" else (error as? ProviderException)?.category ?: "TEST_ERROR",
                0L
            )
        }
        val usage = response?.usage ?: return
        runCatching {
            pl.aimasterpro.app.core.ApiTokenTracker.recordQuery(
                this, config.id, config.model, "TEST", taskId,
                usage.inputTokens, usage.outputTokens, usage.reasoningTokens, usage.cachedTokens,
                usage.status, config.baseUrl, reservationId
            )
        }.onFailure {
            android.util.Log.e("AIMasterBudget", "Nie udało się zapisać użycia testu modelu.", it)
        }
    }

    /** A manual provider/model test is still a real API request and must reserve budget atomically. */
    private fun runBudgetedModelTest(
        config: ProviderConfig,
        testLabel: String,
        systemPrompt: String,
        userPrompt: String,
        scope: ProviderClient.RequestScope
    ): Result<ChatResponse> {
        val taskId = "test-$testLabel-${UUID.randomUUID()}"
        val estimatedInput = (systemPrompt.length.toLong() + userPrompt.length.toLong() + 2L) / 3L
        val planResult = runCatching {
            pl.aimasterpro.app.core.ApiTokenTracker.planBatch(
                this,
                listOf(pl.aimasterpro.app.core.BudgetRequest(
                    taskId = taskId,
                    agentId = "TEST",
                    providerId = config.id,
                    baseUrl = config.baseUrl,
                    modelName = config.model,
                    estimatedInputTokens = estimatedInput,
                    estimatedOutputTokens = 256L
                ))
            )
        }
        if (planResult.isFailure) return Result.failure(planResult.exceptionOrNull()!!)
        val plan = planResult.getOrThrow()
        if (taskId !in plan.allowedTaskIds) {
            pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(plan.reservationId)
            return Result.failure(IllegalStateException(
                plan.blockedReasons[taskId] ?: "Smart Budget wstrzymał test providera/modelu."
            ))
        }
        return try {
            val result = runCatching { client.chat(config, systemPrompt, userPrompt, scope = scope) }
            recordModelTestUsage(config, result.getOrNull(), testLabel, taskId, plan.reservationId, result.exceptionOrNull())
            result
        } finally {
            pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(plan.reservationId)
        }
    }

    private fun testSpecificModel(provider: ProviderConfig, info: ProviderModelInfo) {
        val testConfig = provider.copy(model = info.id)
        if (!providerReady(testConfig)) {
            toast("Uzupełnij klucz API i adres dostawcy przed testem.")
            return
        }
        status.text = "Test modelu ${info.id}…"
        val testScope = client.beginRequestScope()
        executor.execute {
            val result = runBudgetedModelTest(testConfig, "MODEL", "Odpowiadaj krótko po polsku.", "Odpowiedz dokładnie: MODEL OK", testScope)
            runOnUiThread {
                status.text = if (result.isSuccess) "Test modelu: OK" else "Test modelu: błąd"
                val error = result.exceptionOrNull()
                val body = (error as? ProviderException)?.responseBody.orEmpty()
                val message = if (result.isSuccess) "Model odpowiada poprawnie.\n\n${result.getOrNull()?.text.orEmpty()}" else "${error?.message ?: "Nieznany błąd"}\n\n${body.take(2500)}\n\n401 = klucz/autoryzacja; 402 = środki/billing; 403 = brak uprawnień; 404 = model/endpoint; 429 = limit lub quota."
                AlertDialog.Builder(this).setTitle(if (result.isSuccess) "TEST MODELU: OK" else "TEST MODELU: BŁĄD")
                    .setMessage(message).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun providerDialog(old: ProviderConfig) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 4, 20, 4) }
        val name = field("Nazwa", old.name); val url = field("Base URL", old.baseUrl); val model = field("Model ID", old.model)
        val key = field("Klucz API (pozostaw pusty, aby zachować istniejący)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val kindSpinner = Spinner(this)
        val kindOptions = listOf("OpenAI-compatible", "Google Gemini API", "Anthropic API")
        kindSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, kindOptions)
        kindSpinner.setSelection(when (old.kind) { ProviderKind.OPENAI_COMPATIBLE -> 0; ProviderKind.GEMINI -> 1; ProviderKind.ANTHROPIC -> 2 })
        listOf(TextView(this).apply { text = "Typ dostawcy"; setTextColor(white) }, kindSpinner, name, url, model, key).forEach { box.addView(it); if (it is EditText) it.setPadding(dp(12), dp(8), dp(12), dp(8)) }
        AlertDialog.Builder(this).setTitle("Konfiguracja dostawcy").setView(box)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Zapisz") { _, _ ->
                val kind = when (kindSpinner.selectedItemPosition) { 1 -> ProviderKind.GEMINI; 2 -> ProviderKind.ANTHROPIC; else -> ProviderKind.OPENAI_COMPATIBLE }
                var base = url.text.toString().trim()
                if (base.startsWith("http://") && !base.contains("localhost") && !base.contains("127.0.0.1")) {
                    toast("Uwaga: HTTP niezabezpieczony. Preferuj HTTPS.")
                }
                if (base.contains("localhost") || base.contains("127.0.0.1")) toast("Uwaga: localhost wskazuje na urządzenie, na którym działa aplikacja.")
                val enteredKey = key.text.toString().trim().removePrefix("Bearer ").trim()
                val apiKey = enteredKey.ifBlank { old.apiKey.trim().removePrefix("Bearer ").trim() }
                val cfg = ProviderConfig(old.id, name.text.toString().ifBlank { old.name }, kind, base, model.text.toString().trim(), apiKey, old.enabled, old.parentProviderId)
                saveProvider(cfg)
                modelCatalogPrefs.edit().putLong("last_attempt", 0L).apply()
                if (cfg.model.isNotBlank()) {
                    selectedProviderId = cfg.id
                    agentProviders[AgentRole.MASTER.name] = cfg.id
                    chatPrefs.edit().putString("selected_provider", cfg.id)
                        .putString("agent_provider_${AgentRole.MASTER.name}", cfg.id).apply()
                }
                showShell("Modele"); showProviders()
            }.show()
    }

    private fun saveProvider(config: ProviderConfig) {
        val json = JSONObject().put("id", config.id).put("name", config.name).put("kind", config.kind.name)
            .put("baseUrl", config.baseUrl).put("model", config.model).put("apiKey", config.apiKey).put("enabled", config.enabled).put("parentProviderId", config.parentProviderId)
        if (!store.saveConfig(json)) {
            toast("Nie udało się bezpiecznie zapisać klucza API. Konfiguracja nie została zapisana.")
        }
    }

    private fun loadProvider(id: String): ProviderConfig? {
        val j = store.readConfig(id) ?: return null
        val kind = runCatching { ProviderKind.valueOf(j.optString("kind")) }.getOrDefault(ProviderKind.OPENAI_COMPATIBLE)
        val parentId = j.optString("parentProviderId", "")
        val stored = ProviderConfig(j.optString("id", id), j.optString("name", id), kind, j.optString("baseUrl"), j.optString("model"), j.optString("apiKey").trim().removePrefix("Bearer ").trim(), j.optBoolean("enabled", true), parentId)
        if (parentId.isBlank()) return stored
        val parentJson = store.readConfig(parentId) ?: return stored
        val parentKind = runCatching { ProviderKind.valueOf(parentJson.optString("kind")) }.getOrDefault(stored.kind)
        return stored.copy(
            kind = parentKind,
            baseUrl = parentJson.optString("baseUrl", stored.baseUrl),
            apiKey = parentJson.optString("apiKey", stored.apiKey).trim().removePrefix("Bearer ").trim(),
            enabled = parentJson.optBoolean("enabled", stored.enabled)
        )
    }

    private fun testProvider(p: ProviderConfig) {
        if (!providerReady(p)) { toast("Uzupełnij adres, model i klucz API (lokalny Ollama nie wymaga klucza)."); return }
        status.text = "Testowanie ${p.name}…"
        val testScope = client.beginRequestScope()
        executor.execute {
            val r = runBudgetedModelTest(p, "PROVIDER", "Odpowiadaj krótko po polsku.", "Odpowiedz dokładnie: TEST OK", testScope)
            runOnUiThread { AlertDialog.Builder(this).setTitle(if (r.isSuccess) "Test zakończony powodzeniem" else "Test nieudany")
                .setMessage(r.getOrNull()?.text ?: ((r.exceptionOrNull()?.message ?: "Nieznany błąd") + "\n\n" + (r.exceptionOrNull() as? ProviderException)?.responseBody.orEmpty()))
                .setPositiveButton("OK", null).show(); status.text = if (r.isSuccess) "Test API: OK" else "Test API: błąd" }
        }
    }

    private fun discover(p: ProviderConfig) {
        val baseId = p.parentProviderId.ifBlank { p.id }
        val base = loadProvider(baseId) ?: p
        if (!providerCredentialsReady(base)) {
            toast("Uzupełnij Base URL i klucz API w profilu dostawcy.")
            return
        }
        status.text = "Pobieranie modeli: ${base.name}…"
        executor.execute {
            val result = runCatching { client.discoverModelDetails(base) }
            val discovered = result.getOrDefault(emptyList())
            if (result.isSuccess && discovered.isNotEmpty()) {
                saveModelCatalogForProvider(base, discovered)
                val now = System.currentTimeMillis()
                modelCatalogPrefs.edit().putLong("last_attempt", now).putLong("last_sync", now).apply()
            }
            runOnUiThread {
                status.text = if (result.isSuccess && discovered.isNotEmpty()) "Pobrano ${discovered.size} modeli" else "Błąd pobierania modeli"
                showShell("Modele"); showProviders()
                if (result.isSuccess && discovered.isNotEmpty()) toast("Pobrano ${discovered.size} modeli od ${base.name}.")
                else AlertDialog.Builder(this).setTitle("Nie udało się odświeżyć listy modeli")
                    .setMessage(if (result.isSuccess) "API zwróciło pustą listę. Zachowano poprzednie dane." else result.exceptionOrNull()?.message ?: "Nieznany błąd")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun showAgents() {
        loadAgentActivity()
        card("Zespół agentów", "Tutaj przydzielasz gotowe profile modeli do ról, ustawiasz tryb pracy i testujesz połączenie. Katalog modeli oraz klucze API są zarządzane wyłącznie w zakładce MODELE.")

        val mode = currentWorkMode()
        val modeCard = card("Tryb pracy: ${mode.label}", mode.description)
        WorkMode.values().forEach { candidate ->
            action(if (candidate == mode) "✓ ${candidate.label} — aktywny" else candidate.label, modeCard) {
                chatPrefs.edit().putString("work_mode_${currentChatId}", candidate.name).apply()
                showShell("Agenci"); showAgents()
                toast("Ustawiono tryb: ${candidate.label}")
            }
        }
        val catalogCard = card("Biblioteka specjalizacji", "Definicje ról i zadań nie wymagają osobnych kluczy API. Wykonanie każdej roli korzysta z profilu modelu przypisanego poniżej.")
        action("Pokaż pełny katalog agentów", catalogCard) {
            val rows = AgentCatalog.all.mapIndexed { index, agent ->
                "%02d. %s\\n%s\\n%s".format(index + 1, agent.name, agent.category, agent.responsibility)
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Katalog agentów (${AgentCatalog.all.size})")
                .setItems(rows, null).setPositiveButton("Zamknij", null).show()
        }
        val configs = store.allConfigs().mapNotNull { j -> runCatching { loadProvider(j.optString("id")) }.getOrNull() }.filter { providerReady(it) && isModelPresentInCatalog(it) }
        if (configs.isEmpty()) {
            val missing = card("Brak gotowych profili modeli", "Najpierw otwórz MODELE, skonfiguruj dostawcę, pobierz jego listę i utwórz profil wybranego modelu. Wróć tutaj, aby przypisać go agentom.")
            action("OTWÓRZ KATALOG MODELI", missing) { navigate("Modele") }
        }
        AgentRole.values().forEach { role ->
            val assignedId = agentProviders[role.name] ?: "groq"
            val box = card(role.label, role.purpose)
            val state = agentConnection[role.name] ?: "unknown"
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 8, 0, 6) }
            val color = when (state) { "connected" -> Color.rgb(45, 210, 90); "testing" -> orange; "error" -> Color.RED; else -> muted }
            row.addView(TextView(this).apply { text = "●"; textSize = 22f; setTextColor(color) })
            row.addView(TextView(this).apply {
                text = when (state) { "connected" -> "Połączenie potwierdzone"; "testing" -> "Testowanie połączenia…"; "error" -> "Błąd / brak połączenia"; else -> "Nie sprawdzono połączenia" }
                textSize = 12f; setTextColor(muted); setPadding(8, 0, 0, 0)
            })
            box.addView(row)
            val spinner = Spinner(this)
            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
                if (configs.isEmpty()) listOf("Brak profili — skonfiguruj w MODELE") else configs.map { "${it.name} — ${it.model}" })
            val idx = configs.indexOfFirst { it.id == assignedId }.let { if (it < 0) 0 else it }
            if (configs.isNotEmpty()) spinner.setSelection(idx)
            box.addView(TextView(this).apply { text = "Dostawca / model"; textSize = 12f; setTextColor(white); setPadding(0, 8, 0, 0) })
            box.addView(spinner)
            action("Zapisz przypisanie", box) {
                if (configs.isEmpty()) { toast("Najpierw dodaj dostawcę w zakładce Modele."); return@action }
                val cfg = configs[spinner.selectedItemPosition]
                agentProviders[role.name] = cfg.id
                chatPrefs.edit().putString("agent_provider_${role.name}", cfg.id).apply()
                if (role == AgentRole.MASTER) {
                    selectedProviderId = cfg.id
                    chatPrefs.edit().putString("selected_provider", cfg.id).apply()
                }
                agentConnection[role.name] = "unknown"
                showShell("Agenci"); showAgents()
                toast("${role.label}: przypisano ${cfg.name}")
            }
            action("Testuj połączenie agenta", box) {
                if (configs.isEmpty()) { toast("Brak dostawców."); return@action }
                val cfg = configs[spinner.selectedItemPosition]
                agentProviders[role.name] = cfg.id
                chatPrefs.edit().putString("agent_provider_${role.name}", cfg.id).apply()
                if (role == AgentRole.MASTER) {
                    selectedProviderId = cfg.id
                    chatPrefs.edit().putString("selected_provider", cfg.id).apply()
                }
                if (!providerReady(cfg)) {
                    agentConnection[role.name] = "error"
                    showShell("Agenci"); showAgents()
                    toast("Uzupełnij adres, model i klucz API dla ${cfg.name}."); return@action
                }
                agentConnection[role.name] = "testing"
                showShell("Agenci"); showAgents()
                val testScope = client.beginRequestScope()
                executor.execute {
                    val result = runBudgetedModelTest(cfg, "AGENT:${role.name}", "Odpowiadaj krótko po polsku.", "Odpowiedz dokładnie: AGENT OK", testScope)
                    runOnUiThread {
                        agentConnection[role.name] = if (result.isSuccess) "connected" else "error"
                        logAgent("${role.label}: test połączenia ${if (result.isSuccess) "OK" else "BŁĄD"} • ${cfg.name} / ${cfg.model}")
                        showShell("Agenci"); showAgents()
                        if (result.isSuccess) toast("${role.label}: połączenie działa")
                        else AlertDialog.Builder(this).setTitle("Test agenta nieudany")
                            .setMessage((result.exceptionOrNull()?.message ?: "Nieznany błąd API") + "\n\n" + (result.exceptionOrNull() as? ProviderException)?.responseBody.orEmpty())
                            .setPositiveButton("OK", null).show()
                    }
                }
            }
            action("Użyj w czacie", box) { selectedAgent = role; showShell("Czat"); showChat(); toast("Wybrano agenta: ${role.label}") }
        }
        val logCard = card("Rozmowy i komunikacja między agentami", "Śledź w czasie rzeczywistym dialogi, zadania, raporty oraz błędy (np. limit tokenów, awarie API).")
        action("💬 Otwórz podgląd rozmów miedzy agentami (Live / Błędy)", logCard) { showAgentCollaborationViewer() }
        val agentScreenActivityRows = agentActivitySnapshot()
        if (agentScreenActivityRows.isEmpty()) logCard.addView(TextView(this).apply { text = "Brak zarejestrowanych rozmów agentów."; setTextColor(muted); setPadding(0, 8, 0, 0) })
        agentScreenActivityRows.asReversed().take(30).forEach { line -> logCard.addView(TextView(this).apply { text = "• $line"; setTextColor(white); textSize = 13f; setPadding(0, 7, 0, 0); setTextIsSelectable(true) }) }
        action("Kopiuj dziennik rozmów agentów", logCard) { copyText(agentActivitySnapshot().joinToString("\n")) }
    }

    private fun applyRecommendedModel(role: AgentRole) {
        val rec = pl.aimasterpro.app.core.FreeModelsCatalog.getRecommendedModel(role.name, this)
        if (rec == null) {
            AlertDialog.Builder(this).setTitle("Brak potwierdzonej rekomendacji")
                .setMessage("Najpierw odśwież katalog. Rekomendacje pojawią się dopiero dla modeli, których darmowy status został jednoznacznie potwierdzony.")
                .setPositiveButton("OK", null).show()
            return
        }
        assignModelToAgent(role, rec)
    }

    private fun assignModelToAgent(role: AgentRole, modelSpec: pl.aimasterpro.app.core.FreeModelSpec) {
        if (!modelSpec.freeStatus.isAllowedForSelection || !pl.aimasterpro.app.core.FreeModelsCatalog.isFreeModel(modelSpec.providerId, modelSpec.modelId)) {
            toast("Ten model nie ma potwierdzonego darmowego statusu. Nie można go przypisać.")
            return
        }
        val existingConfig = loadProvider(modelSpec.providerConfigId) ?: loadProvider(modelSpec.providerId)
        val finalConfig = if (existingConfig != null) {
            existingConfig.copy(model = modelSpec.modelId)
        } else {
            val kind = when (modelSpec.providerId.lowercase()) {
                "gemini" -> ProviderKind.GEMINI
                "anthropic" -> ProviderKind.ANTHROPIC
                else -> ProviderKind.OPENAI_COMPATIBLE
            }
            val baseUrl = when (modelSpec.providerId.lowercase()) {
                "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai"
                "groq" -> "https://api.groq.com/openai/v1"
                "openrouter" -> "https://openrouter.ai/api/v1"
                "mistral" -> "https://api.mistral.ai/v1"
                "ollama" -> "http://10.0.2.2:11434/v1"
                else -> "https://api.openai.com/v1"
            }
            ProviderConfig(modelSpec.providerId, modelSpec.providerName, kind, baseUrl, modelSpec.modelId, "", true)
        }
        if (!providerReady(finalConfig)) {
            AlertDialog.Builder(this).setTitle("Brak konfiguracji dostawcy")
                .setMessage("Model jest oznaczony jako darmowy, ale profil ${modelSpec.providerName} nie ma kompletnego adresu API i klucza. Najpierw uzupełnij profil dostawcy w zakładce Modele. Nie zapisano pustego klucza ani nieaktywnej konfiguracji.")
                .setPositiveButton("Przejdź do modeli") { _, _ -> showShell("Modele"); showProviders() }
                .setNegativeButton("Anuluj", null).show()
            return
        }
        saveProvider(finalConfig)
        agentProviders[role.name] = finalConfig.id
        chatPrefs.edit().putString("agent_provider_${role.name}", finalConfig.id).apply()
        if (role == AgentRole.MASTER) {
            selectedProviderId = finalConfig.id
            chatPrefs.edit().putString("selected_provider", finalConfig.id).apply()
        }
        showShell("Agenci")
        showAgents()

        AlertDialog.Builder(this)
            .setTitle("MODEL ZOSTAŁ PRZYPISANY")
            .setMessage("Agent: ${role.label}\nDostawca: ${modelSpec.providerName}\nModel: ${modelSpec.modelName}\nModel ID: ${modelSpec.modelId}\nKategoria: ${modelSpec.freeStatus.label}\n\nKonfiguracja została trwale zapisana.")
            .setPositiveButton("OK", null)
            .show()
    }

    private fun showFreeModelPickerForAgent(role: AgentRole) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val titleTv = TextView(this).apply {
            text = "Modele z potwierdzonym darmowym dostępem • ${role.label}"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
        }
        dialogView.addView(titleTv)

        val updatedTv = TextView(this).apply {
            text = "Ostatnia synchronizacja: ${pl.aimasterpro.app.core.FreeModelsCatalog.lastUpdatedFormatted(this@MainActivity)}"
            textSize = 11f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(6))
        }
        dialogView.addView(updatedTv)
        val catalogSummaryTv = TextView(this).apply {
            textSize = 12f
            setTextColor(cyan)
            setPadding(0, 0, 0, dp(10))
        }
        dialogView.addView(catalogSummaryTv)

        val refreshRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(8)) }
        val refreshBtn = TextView(this).apply {
            text = "🔄 Odśwież katalog z API"
            textSize = 12f
            setTextColor(cyan)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = ripple(panel, 10)
        }
        refreshRow.addView(refreshBtn)
        dialogView.addView(refreshRow)

        val searchField = field("🔍 Szukaj darmowego modelu…")
        dialogView.addView(searchField)

        val modelsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(modelsContainer)
        }
        dialogView.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)).apply { topMargin = dp(8) })

        val dialog = AlertDialog.Builder(this).setView(dialogView).create()

        fun populateList(query: String) {
            modelsContainer.removeAllViews()
            val all = pl.aimasterpro.app.core.FreeModelsCatalog.loadCatalog(this)
            val verifiedCount = all.count { it.freeStatus.isAllowedForSelection && pl.aimasterpro.app.core.FreeModelsCatalog.isFreeModel(it.providerId, it.modelId) }
            val unknownCount = all.count { it.freeStatus == pl.aimasterpro.app.core.FreeStatus.UNKNOWN }
            catalogSummaryTv.text = "Potwierdzone darmowe: $verifiedCount   •   Niezweryfikowane: $unknownCount (zablokowane)"
            val filtered = all.filter {
                it.freeStatus.isAllowedForSelection &&
                pl.aimasterpro.app.core.FreeModelsCatalog.isFreeModel(it.providerId, it.modelId) &&
                (query.isBlank() || it.modelName.contains(query, true) || it.modelId.contains(query, true) || it.providerName.contains(query, true))
            }
            if (filtered.isEmpty()) {
                modelsContainer.addView(TextView(this).apply {
                    text = "Nie ma jeszcze modeli z potwierdzonym darmowym dostępem.\n\n1. Zapisz klucz API dostawcy w zakładce Modele.\n2. Kliknij „Odśwież katalog z API”.\n3. Modele bez wiarygodnego potwierdzenia ceny pozostaną ukryte.\n\nTo celowe zabezpieczenie przed przypadkowym użyciem płatnego modelu."
                    setTextColor(muted); setPadding(0, dp(16), 0, 0); textSize = 13f
                })
                return
            }
            filtered.forEach { spec ->
                val currentAssignedId = agentProviders[role.name]
                val currentAssignedConfig = if (currentAssignedId != null) loadProvider(currentAssignedId) else null
                val isSelected = currentAssignedConfig != null && currentAssignedConfig.model == spec.modelId

                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(if (isSelected) panel else cardBg, 12, if (isSelected) orange else border)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.bottomMargin = dp(8)
                    layoutParams = lp
                    setOnClickListener {
                        dialog.dismiss()
                        assignModelToAgent(role, spec)
                    }
                }

                val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val provName = TextView(this).apply { text = spec.providerName; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(cyan) }
                val freeBadge = TextView(this).apply {
                    text = " [${spec.freeStatus.label}] "
                    textSize = 10f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (spec.freeStatus == pl.aimasterpro.app.core.FreeStatus.FREE) Color.rgb(45, 210, 90) else orange)
                }
                row1.addView(provName)
                row1.addView(freeBadge, LinearLayout.LayoutParams(-2, -2).apply { weight = 1f })
                if (isSelected) {
                    val selTv = TextView(this).apply { text = "✓ WYBRANY"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(orange) }
                    row1.addView(selTv)
                }
                card.addView(row1)

                val nameTv = TextView(this).apply { text = spec.modelName; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(white); setPadding(0, dp(2), 0, 0) }
                val idTv = TextView(this).apply { text = "Model ID: ${spec.modelId}"; textSize = 12f; setTextColor(muted) }
                val infoTv = TextView(this).apply { text = "Dostępność: niezweryfikowana na żywo • ${spec.rateLimitInfo}\nŹródło: ${spec.verificationSource}"; textSize = 11f; setTextColor(muted) }
                card.addView(nameTv)
                card.addView(idTv)
                card.addView(infoTv)

                modelsContainer.addView(card)
            }
        }

        fun refreshCatalog(showToast: Boolean) {
            refreshBtn.text = "⏳ Pobieranie z API…"
            refreshBtn.isEnabled = false
            executor.execute {
                val savedConfigs = store.allConfigs().mapNotNull { j -> runCatching { loadProvider(j.optString("id")) }.getOrNull() }
                val discoveredList = mutableListOf<pl.aimasterpro.app.core.FreeModelSpec>()
                val refreshedProviders = mutableSetOf<String>()
                savedConfigs.forEach { cfg ->
                    if (providerReady(cfg)) {
                        val result = runCatching { client.discoverModels(cfg) }
                        if (result.isSuccess) {
                            val canonicalProvider = when {
                                cfg.baseUrl.contains("openrouter.ai", true) -> "openrouter"
                                cfg.baseUrl.contains("groq.com", true) -> "groq"
                                cfg.baseUrl.contains("generativelanguage.googleapis.com", true) -> "gemini"
                                cfg.baseUrl.contains("api.mistral.ai", true) -> "mistral"
                                cfg.baseUrl.contains("11434") || cfg.name.contains("ollama", true) -> "ollama"
                                else -> cfg.id
                            }
                            refreshedProviders.add(canonicalProvider)
                            result.getOrDefault(emptyList()).forEach { mid ->
                                val spec = pl.aimasterpro.app.core.FreeModelsCatalog.classifyDiscoveredModel(canonicalProvider, mid, cfg.name, cfg.id)
                                if (discoveredList.none { it.providerId == spec.providerId && it.modelId == spec.modelId }) discoveredList.add(spec)
                            }
                        }
                    }
                }

                // Keep cached entries for providers that could not be reached, but never call them live.
                val previous = pl.aimasterpro.app.core.FreeModelsCatalog.loadCatalog(this@MainActivity)
                previous.filter { it.providerId !in refreshedProviders }.forEach { cached ->
                    if (discoveredList.none { it.providerId == cached.providerId && it.modelId == cached.modelId })
                        discoveredList.add(cached.copy(isOnline = false))
                }
                if (refreshedProviders.isNotEmpty()) {
                    pl.aimasterpro.app.core.FreeModelsCatalog.saveCatalog(this@MainActivity, discoveredList)
                }
                runOnUiThread {
                    refreshBtn.text = "↻ Odśwież katalog"
                    refreshBtn.isEnabled = true
                    if (refreshedProviders.isNotEmpty()) {
                        updatedTv.text = "Ostatnia synchronizacja: ${pl.aimasterpro.app.core.FreeModelsCatalog.lastUpdatedFormatted(this@MainActivity)}"
                    }
                    populateList(searchField.text.toString())
                    val freeCount = discoveredList.count { it.freeStatus.isAllowedForSelection }
                    val unknownCount = discoveredList.count { it.freeStatus == pl.aimasterpro.app.core.FreeStatus.UNKNOWN }
                    if (showToast) {
                        val message = if (refreshedProviders.isEmpty())
                            "Nie udało się odświeżyć API. Zachowano ostatni zapisany katalog."
                        else "Katalog: $freeCount darmowych, $unknownCount niezweryfikowanych (zablokowane)."
                        toast(message)
                    }
                }
            }
        }
        refreshBtn.setOnClickListener { refreshCatalog(true) }

        populateList("")
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { populateList(s?.toString().orEmpty()) }
        })

        dialog.window?.setBackgroundDrawable(rounded(panel, 20))
        dialog.show()
        // Automatic sync whenever the catalog is opened; cached data remains visible while loading.
        refreshCatalog(false)
    }

    /** Requires an explicit second confirmation and applies only to the isolated project workspace. */
    private fun confirmAndApplyPatch(
        preview: pl.aimasterpro.app.core.ProjectPatchEngine.Preview,
        path: String,
        decision: pl.aimasterpro.app.core.PatchPeerReviewEngine.ReviewDecision,
        previewWorkspaceIdentity: String,
        previewChatId: String
    ) {
        if (!preview.accepted || decision.state != pl.aimasterpro.app.core.PatchPeerReviewEngine.ReviewState.READY_FOR_HUMAN_APPROVAL) {
            toast("Patch nie spełnia warunków zatwierdzenia.")
            return
        }
        val paths = preview.files.map { it.path }
        val description = buildString {
            appendLine("Plików do zmiany: ${preview.files.size}")
            preview.files.forEach { appendLine("${it.operation}: ${it.path}") }
            appendLine("\nRecenzenci: dwa pozytywne, strukturalne werdykty na różnych profilach.")
            appendLine("\nPo zatwierdzeniu zostanie utworzony snapshot, a patch zostanie zapisany wyłącznie w prywatnym workspace aplikacji.")
            appendLine("Oryginalne archiwum ZIP, katalog projektu na komputerze i zainstalowana aplikacja nie zostaną zmienione.")
            appendLine("\nTesty projektu nie zostały jeszcze uruchomione. Status po zapisie: APPLIED_UNVERIFIED.")
        }
        AlertDialog.Builder(this)
            .setTitle("Ostateczne zatwierdzenie patcha")
            .setMessage(description)
            .setPositiveButton("TAK — ZASTOSUJ W WORKSPACE") { _, _ ->
                val progress = AlertDialog.Builder(this)
                    .setTitle("Bezpieczne stosowanie patcha")
                    .setMessage("Ponowna kontrola SHA-256, utworzenie snapshotu i atomowa podmiana workspace…")
                    .setCancelable(false)
                    .create()
                progress.show()
                val chatId = previewChatId
                Thread {
                    var changeSetId = ""
                    var applied: pl.aimasterpro.app.core.ProjectWorkspaceStore.ApplyResult? = null
                    var snapshotRecorded = false
                    var statusPersisted = false
                    var compensationReport = ""
                    val outcome = runCatching {
                        check(isCurrentChat(chatId)) { "Rozmowa źródłowa preview została zmieniona. Przygotuj patch ponownie w aktywnym czacie." }
                        val workspaceStore = pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace"))
                        val contentByPath = preview.files.mapNotNull { file -> file.resultContent?.let { file.path to it } }.toMap()
                        val validation = pl.aimasterpro.app.core.ChangeSafetyGuard.validate(
                            paths, contentByPath, maxFiles = 20, maxTotalChars = 2_000_000
                        )
                        check(validation.allowed) { "Dodatkowa kontrola bezpieczeństwa zablokowała zapis: ${validation.findings.filter { it.blocking }.joinToString { it.code }}" }
                        val changeSet = pl.aimasterpro.app.core.ChangeSetStore.propose(
                            this,
                            taskId = "patch-${UUID.randomUUID()}",
                            summary = "Patch po Peer Review: ${path.take(180)} (${preview.files.size} plików)",
                            paths = paths,
                            validation = validation,
                            chatId = chatId
                        )
                        changeSetId = changeSet.id
                        check(pl.aimasterpro.app.core.ChangeSetStore.transition(this, changeSetId, "APPROVED")) {
                            "Nie udało się zapisać jawnej zgody w rejestrze zmian."
                        }
                        applied = pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                            check(isCurrentChat(chatId)) { "Rozmowa źródłowa zmieniła się przed zastosowaniem patcha." }
                            pl.aimasterpro.app.core.ProjectWorkspaceIdentityGuard.requireUnchanged(
                                previewWorkspaceIdentity, workspaceStore.currentIdentity()
                            )
                            val integrity = workspaceStore.verifyCurrentWorkspace()
                            check(integrity.valid) {
                                "Workspace zmienił się od czasu preview/recenzji. Wymagane ponowne przygotowanie patcha."
                            }
                            workspaceStore.applyPreview(preview, changeSetId = changeSetId, chatId = chatId)
                        }
                        check(pl.aimasterpro.app.core.ChangeSetStore.recordSnapshot(this, changeSetId, applied!!.snapshot.id)) {
                            "Patch zastosowano, ale nie udało się powiązać snapshotu z rejestrem zmian."
                        }
                        snapshotRecorded = true
                        check(pl.aimasterpro.app.core.ChangeSetStore.transition(this, changeSetId, "APPLIED_UNVERIFIED")) {
                            "Patch zastosowano, ale nie udało się zaktualizować statusu rejestru zmian."
                        }
                        statusPersisted = true
                        check(activateChangeSetForCurrentChat(chatId, changeSetId, applied!!.workspaceIdentity)) {
                            "Patch zapisano, ale rozmowa źródłowa nie jest już aktywna lub nie udało się trwale ustawić zakresu dowodów."
                        }
                        check(pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                            // Receipt acknowledgement is a workspace mutation too. Recheck the
                            // exact workspace generation while holding the shared lock so an
                            // import/new patch cannot slip between activation and receipt removal.
                            pl.aimasterpro.app.core.ProjectWorkspaceIdentityGuard.requireUnchanged(
                                applied!!.workspaceIdentity, workspaceStore.currentIdentity()
                            )
                            workspaceStore.acknowledgePatchReceipt(changeSetId, applied!!.transactionId)
                        }) {
                            "Patch zapisano i ustawiono zakres, ale nie udało się potwierdzić uzgodnienia dziennika transakcji."
                        }
                        applied!!
                    }
                    if (outcome.isFailure && changeSetId.isNotBlank()) {
                        if (applied == null) {
                            // The pending-transaction decision and pointer invalidation must be
                            // serialized with patch/import/recovery. A naked filesystem probe here
                            // could observe CLEAR, then race with a new patch before REJECTED is
                            // persisted or the active pointer is invalidated.
                            val workspaceHasPendingTransaction = pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                                val pending = runCatching {
                                    pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace")).hasPendingWorkspaceTransaction()
                                }.getOrDefault(true)
                                if (!pending) {
                                    runCatching { pl.aimasterpro.app.core.ChangeSetStore.transition(this, changeSetId, "REJECTED") }
                                }
                                runCatching { pl.aimasterpro.app.core.HostActiveChangeSetStore.invalidate(this, changeSetId) }
                                pending
                            }
                            if (workspaceHasPendingTransaction) {
                                compensationReport = "Wynik podmiany workspace wymaga odzyskania; ChangeSet pozostawiono w stanie APPROVED, aby nie oznaczyć zastosowanego patcha jako odrzuconego."
                            }
                        } else if (!statusPersisted) {
                            // Compensate only failures before the ChangeSet reached its durable
                            // APPLIED_UNVERIFIED state. Never roll back over a newer workspace.
                            val compensation = runCatching {
                                pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                                    pl.aimasterpro.app.core.ProjectWorkspaceIdentityGuard.requireUnchanged(
                                        applied!!.workspaceIdentity, pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace")).currentIdentity()
                                    )
                                    val store = pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace"))
                                    check(store.markPatchReceiptRollbackRequested(changeSetId, applied!!.transactionId)) {
                                        "Nie udało się trwale zapisać zamiaru rollbacku patcha."
                                    }
                                    store.restoreSnapshot(applied!!.snapshot.id)
                                    check(store.markPatchReceiptRolledBack(changeSetId, applied!!.transactionId)) {
                                        "Workspace przywrócono, ale nie udało się trwale potwierdzić rollbacku."
                                    }
                                    val pointerCleared = runCatching {
                                        pl.aimasterpro.app.core.HostActiveChangeSetStore.invalidate(this, changeSetId)
                                    }.getOrDefault(false)
                                    pointerCleared
                                }
                            }
                            if (compensation.isSuccess) {
                                val pointerCleared = compensation.getOrThrow()
                                val ledgerRepair = runCatching {
                                    if (snapshotRecorded) {
                                        val state = pl.aimasterpro.app.core.ChangeSetStore.readResult(this)
                                        val current = state.items.singleOrNull { it.id == changeSetId }
                                        when (current?.status) {
                                            "APPROVED" -> {
                                                check(pl.aimasterpro.app.core.ChangeSetStore.transition(this, changeSetId, "APPLIED_UNVERIFIED"))
                                                check(pl.aimasterpro.app.core.ChangeSetStore.transition(this, changeSetId, "ROLLED_BACK"))
                                            }
                                            "APPLIED_UNVERIFIED", "APPLIED" -> check(pl.aimasterpro.app.core.ChangeSetStore.transition(this, changeSetId, "ROLLED_BACK"))
                                            "ROLLED_BACK" -> Unit
                                            else -> error("Nie można uzgodnić statusu rejestru po rollbacku (${current?.status ?: state.status.name}).")
                                        }
                                    } else {
                                        check(pl.aimasterpro.app.core.ChangeSetStore.transition(this, changeSetId, "REJECTED"))
                                    }
                                }
                                val receiptAck: Result<Boolean> = if (ledgerRepair.isSuccess) runCatching {
                                    pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                                        pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace"))
                                            .acknowledgePatchReceipt(changeSetId, applied!!.transactionId)
                                    }
                                } else Result.failure(IllegalStateException("Rejestr nie został uzgodniony."))
                                compensationReport = buildString {
                                    append("Rollback kompensacyjny wykonany; snapshot ${applied!!.snapshot.id} przywrócony.")
                                    if (!pointerCleared) append(" Active pointer nie został potwierdzony jako usunięty; workspace identity nadal blokuje stary scope.")
                                    if (ledgerRepair.isFailure) append(" Rejestr wymaga kontroli: ${ledgerRepair.exceptionOrNull()?.message ?: "błąd"}.")
                                    else append(" Rejestr uzgodniony: ${if (snapshotRecorded) "ROLLED_BACK" else "REJECTED"}.")
                                    if (!receiptAck.getOrDefault(false)) append(" Potwierdzenie transakcji pozostawiono do odzyskania po restarcie.")
                                }
                            } else {
                                compensationReport = "Rollback kompensacyjny zablokowany/nieudany: ${compensation.exceptionOrNull()?.message ?: "błąd"}. Nie nadpisano workspace o innej tożsamości; wymagany przegląd stanu."
                            }
                        } else {
                            runCatching { pl.aimasterpro.app.core.HostActiveChangeSetStore.invalidate(this, changeSetId) }
                            compensationReport = "Patch został zastosowany i zapisany jako APPLIED_UNVERIFIED, ale nie udało się ustawić aktywnego zakresu dowodów. Zmiana pozostaje w workspace; nie powtarzaj patcha."
                        }
                    }
                    val report = outcome.fold({ result ->
                        logAgentForChat(chatId, "✅ Patch zastosowano w prywatnym workspace. Snapshot ${result.snapshot.id}; status APPLIED_UNVERIFIED; testy NOT_RUN.")
                        "PATCH ZASTOSOWANY W WORKSPACE\n\nSnapshot: ${result.snapshot.id}\nPliki: ${result.changedPaths.joinToString()}\nStatus: APPLIED_UNVERIFIED\nRejestr zmian: $changeSetId\n\nOryginalne pliki poza prywatnym workspace nie zostały zmienione. Testy projektu pozostają NOT_RUN; kontrola integralności nie uruchamia kodu."
                    }, { error ->
                        val activityMessage = when {
                            applied != null && statusPersisted -> "⚠️ Patch zastosowano, ale aktywny zakres dowodów nie został potwierdzony: ${error.message ?: error.javaClass.simpleName}"
                            applied != null && compensationReport.startsWith("Rollback kompensacyjny wykonany") -> "↩️ Patch wycofano po błędzie zapisu rejestru: ${error.message ?: error.javaClass.simpleName}"
                            else -> "⛔ Patch nie został potwierdzony: ${error.message ?: error.javaClass.simpleName}"
                        }
                        logAgentForChat(chatId, activityMessage)
                        val appliedWarning = if (applied != null) "\n\nUWAGA: ${compensationReport.ifBlank { "Patch został zapisany, ale operacja nie zakończyła się w pełni." }} Snapshot: ${applied!!.snapshot.id}." else "\n\nNie uzyskano potwierdzenia zakończenia patcha. Sprawdź komunikat o automatycznym przywróceniu; jeśli przywrócenie również zawiodło, nie kontynuuj bez kontroli manifestu workspace."
                        "PATCH NIE ZOSTAŁ POTWIERDZONY\n\n${error.message ?: error.javaClass.simpleName}$appliedWarning"
                    })
                    runOnUiThread {
                        if (progress.isShowing) progress.dismiss()
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val view = TextView(this).apply {
                            text = report
                            textSize = 12f
                            setTextIsSelectable(true)
                            setPadding(dp(16), dp(12), dp(16), dp(12))
                        }
                        AlertDialog.Builder(this).setTitle(if (outcome.isSuccess) "Patch zapisany" else "Wynik operacji patcha")
                            .setView(ScrollView(this).apply { addView(view) })
                            .setPositiveButton("Kopiuj raport") { _, _ -> copyText(report) }
                            .setNegativeButton("Zamknij", null).show()
                    }
                }.apply { name = "ApplyReviewedPatch" }.start()
            }
            .setNegativeButton("Anuluj", null)
            .show()
    }

    /** Runs two patch reviewers only when two distinct, verified-free model profiles are assigned. */
    private fun runPatchPeerReview(
        reviewPackage: pl.aimasterpro.app.core.PatchPeerReviewEngine.ReviewPackage,
        path: String,
        preview: pl.aimasterpro.app.core.ProjectPatchEngine.Preview,
        previewWorkspaceIdentity: String,
        previewChatId: String
    ) {
        if (!reviewPackage.acceptedForReview) {
            toast("Lokalny audyt zablokował recenzję patcha.")
            return
        }
        val reviewerIds = listOf("critical-reviewer", "integration-engineer", "repository-researcher")
        val assignments = reviewerIds.mapNotNull { id ->
            val spec = AgentCatalog.find(id) ?: return@mapNotNull null
            val role = AgentProviderRouting.roleFor(spec)
            val configuredId = agentProviders[role.name] ?: return@mapNotNull null
            val config = loadProvider(configuredId)?.takeIf { providerReady(it) && isVerifiedFreeConfig(it) }
                ?: return@mapNotNull null
            val fingerprint = "${config.baseUrl.trimEnd('/').lowercase(java.util.Locale.ROOT)}:${config.model.lowercase(java.util.Locale.ROOT)}"
            pl.aimasterpro.app.agents.PatchPeerReviewPlanner.Assignment(spec, fingerprint)
        }
        val requestId = UUID.randomUUID().toString()
        val planned = pl.aimasterpro.app.agents.PatchPeerReviewPlanner.createTasks(
            reviewerPrompt = reviewPackage.reviewerPrompt,
            path = path,
            assignments = assignments,
            requestId = requestId,
            maxReviewers = 2
        )
        if (planned.size < 2) {
            AlertDialog.Builder(this)
                .setTitle("Brak dwóch niezależnych recenzentów")
                .setMessage("Automatyczna recenzja wymaga dwóch różnych agentów z różnymi, jawnie przypisanymi i zweryfikowanymi darmowymi profilami modelowymi.\n\nPrzypisz różne darmowe modele do ról Recenzent, Programista i Badacz. Nie użyję płatnego fallbacku ani nie przedstawię dwóch wywołań tego samego modelu jako niezależnych opinii.")
                .setPositiveButton("OK", null).show()
            return
        }

        val configByAgent = planned.associate { task ->
            val spec = AgentCatalog.find(task.agentId)!!
            val role = AgentProviderRouting.roleFor(spec)
            val profileId = agentProviders[role.name]!!
            task.agentId to loadProvider(profileId)!!
        }
        val baseConfig = configByAgent.values.first()
        val tasks = planned.map { task ->
            TeamTask(
                id = task.id,
                agentId = task.agentId,
                goal = task.goal,
                prompt = task.prompt,
                writeScope = emptyList(),
                reviewOfAgentId = "patch-author"
            )
        }
        val budgetRequests = tasks.map { task ->
            val spec = AgentCatalog.find(task.agentId)!!
            val config = configByAgent[task.agentId]!!
            pl.aimasterpro.app.core.BudgetRequest(
                taskId = task.id,
                agentId = task.agentId,
                providerId = config.id,
                baseUrl = config.baseUrl,
                modelName = config.model,
                estimatedInputTokens = ((task.prompt.length + spec.systemPrompt.length + 2) / 3).toLong(),
                estimatedOutputTokens = 1_200L
            )
        }
        val reviewBudget = pl.aimasterpro.app.core.ApiTokenTracker.planBatch(
            this, budgetRequests, globalReserveTokens = 1_000L
        )
        if (reviewBudget.allowedTaskIds.size < 2) {
            pl.aimasterpro.app.core.ApiTokenTracker.releaseBatchReservation(reviewBudget.reservationId)
            val reasons = reviewBudget.blockedReasons.values.distinct().joinToString("\n").ifBlank { "Nie udało się zarezerwować pełnego budżetu na dwie recenzje." }
            AlertDialog.Builder(this).setTitle("Smart Budget — recenzja zablokowana")
                .setMessage("Wymagane są dwie pełne recenzje; nie uruchamiam tylko jednej.\n\n$reasons")
                .setPositiveButton("OK", null).show()
            return
        }

        val chatId = currentChatId.ifBlank { "patch-review" }
        val reviewMode = currentWorkMode()
        val progress = AlertDialog.Builder(this)
            .setTitle("Niezależny Peer Review patcha")
            .setMessage("Uruchamiam dwóch recenzentów na różnych darmowych profilach. Żaden plik nie zostanie zmieniony…")
            .setCancelable(false)
            .create()
        progress.show()
        logAgentForChat(chatId, "🛡️ Patch Peer Review: uruchamiam ${tasks.size} recenzentów na odrębnych profilach; Smart Budget zarezerwował ${reviewBudget.estimatedReservedTokens} tokenów.")

        Thread {
            val results = try {
                TeamRunEngine(client, maxParallel = 2).use { engine ->
                    engine.runBatch(
                        tasks = tasks,
                        provider = baseConfig,
                        agentProvidersMap = configByAgent,
                        fallbackProviders = emptyList(),
                        isFreeProvider = { candidate -> isVerifiedFreeConfig(candidate) },
                        mode = reviewMode,
                        projectContext = "Recenzja patcha dla pliku $path. Treść diffu znajduje się w poleceniu recenzenta.",
                        onLog = { sender, recipient, message, eventStatus ->
                            logAgentDialogueForChat(chatId, sender, recipient, message, eventStatus)
                        },
                        onUsage = { agentId, taskId, actualProvider, usage ->
                            pl.aimasterpro.app.core.ApiTokenTracker.recordQuery(
                                this, actualProvider.id, actualProvider.model, agentId, taskId,
                                usage.inputTokens, usage.outputTokens, usage.reasoningTokens,
                                usage.cachedTokens, usage.status, actualProvider.baseUrl, reviewBudget.reservationId
                            )
                        },
                        rankProviders = { candidates -> pl.aimasterpro.app.core.ModelHealthTracker.rank(this, candidates) },
                        onProviderHealth = { model, success, category, latency ->
                            pl.aimasterpro.app.core.ModelHealthTracker.record(this, model, success, category, latency)
                        },
                        persistedCooldownMs = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.remainingMs(this, model) }.getOrDefault(0L) },
                        onRateLimited = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.markRateLimited(this, model) }.getOrDefault(0L) },
                        onProviderSuccess = { model -> runCatching { pl.aimasterpro.app.core.ProviderCooldownStore.markSuccess(this, model) } },
                        onTaskState = { task, state, detail, providerId, model, attempts ->
                            val saved = pl.aimasterpro.app.core.TaskCheckpointStore.record(
                                this,
                                pl.aimasterpro.app.core.TaskCheckpointStore.Event(
                                    task.id, task.agentId, state, detail, System.currentTimeMillis(), providerId, model, attempts
                                )
                            )
                            if (!saved) android.util.Log.e("AIMasterRecovery", "Nie udało się trwale zapisać checkpointu zadania.")
                        },
                        requestScope = client.beginRequestScope(),
                        onTaskFinished = { taskId -> pl.aimasterpro.app.core.ApiTokenTracker.completeReservationTask(reviewBudget.reservationId, taskId) }
                    )
                }
            } catch (error: Exception) {
                logAgentForChat(chatId, "⛔ Patch Peer Review zakończony błędem: ${error.message ?: error.javaClass.simpleName}")
                emptyList()
            } finally {
                if (reviewBudget.reservationId.isNotBlank()) {
                    pl.aimasterpro.app.core.ApiTokenTracker.requestBatchReservationRelease(reviewBudget.reservationId)
                }
            }
            val completed = results.filter { it.status == pl.aimasterpro.app.agents.TeamTaskResult.Status.COMPLETED }
            val decision = pl.aimasterpro.app.core.PatchPeerReviewEngine.combine(
                reviewPackage,
                completed.map { pl.aimasterpro.app.core.PatchPeerReviewEngine.ReviewerResult(it.agentId, it.report) },
                minimumIndependentReviewers = 2
            )
            results.forEach { result ->
                logAgentForChat(chatId, "🛡️ Patch review ${result.agentId}: ${result.status} • ${result.providerId}/${result.model} • ${result.report.take(220).replace("\n", " ")}")
                pl.aimasterpro.app.core.LearningJournalStore.record(
                    this,
                    pl.aimasterpro.app.core.LearningJournalStore.Event(
                        timestamp = System.currentTimeMillis(), kind = "patch_peer_review", agentId = result.agentId,
                        providerId = result.providerId, model = result.model, status = result.status.name,
                        durationMs = result.durationMs, attempts = result.attempts, category = "patch_peer_review",
                        taskId = result.taskId
                    )
                )
            }
            val report = buildString {
                appendLine("PATCH PEER REVIEW — WYNIK")
                appendLine("Plik: $path")
                appendLine("Stan: ${decision.state}")
                appendLine(decision.summary)
                appendLine("\nWERYFIKOWALNE WERDYKTY")
                decision.parsedVerdicts.forEach { (agent, verdict) -> appendLine("$agent: $verdict") }
                appendLine("\nZGŁOSZONE PROBLEMY")
                if (decision.issues.isEmpty()) appendLine("Brak poprawnie sformatowanych ISSUE.")
                else decision.issues.forEach { appendLine(it) }
                appendLine("\nWYKONANIE")
                results.forEach { appendLine("${it.agentId}: ${it.status} • ${it.providerId}/${it.model} • ${it.durationMs} ms") }
                appendLine("\nRecenzja nie zatwierdza i nie stosuje patcha. Zapis wymaga osobnego, jawnego potwierdzenia użytkownika.")
            }
            runOnUiThread {
                if (progress.isShowing) progress.dismiss()
                if (isFinishing || isDestroyed) return@runOnUiThread
                val view = TextView(this).apply {
                    text = report
                    textSize = 12f
                    setTextIsSelectable(true)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                AlertDialog.Builder(this).setTitle("Werdykt recenzentów: ${decision.state}")
                    .setView(ScrollView(this).apply { addView(view) })
                    .setPositiveButton(if (decision.state == pl.aimasterpro.app.core.PatchPeerReviewEngine.ReviewState.READY_FOR_HUMAN_APPROVAL) "ZATWIERDŹ I ZASTOSUJ" else "Zamknij") { _, _ ->
                        if (decision.state == pl.aimasterpro.app.core.PatchPeerReviewEngine.ReviewState.READY_FOR_HUMAN_APPROVAL) {
                            confirmAndApplyPatch(preview, path, decision, previewWorkspaceIdentity, previewChatId)
                        }
                    }
                    .setNeutralButton("Kopiuj raport") { _, _ -> copyText(report) }
                    .setNegativeButton("Anuluj", null).show()
            }
        }.apply { name = "PatchPeerReview" }.start()
    }

    private fun showCommandCenter() {
        val col = content
        col.setPadding(dp(16), dp(14), dp(16), dp(24))
        val now = System.currentTimeMillis()
        val budget = pl.aimasterpro.app.core.ApiTokenTracker.budgetUsageSnapshot(this)
        val quality = pl.aimasterpro.app.core.QualityLearningStore.summary(this)
        val events = pl.aimasterpro.app.core.LearningJournalStore.load(this)
        val agentStats = pl.aimasterpro.app.core.AgentEfficiencyTracker.stats(this)
        val memories = pl.aimasterpro.app.core.AgentOperationalMemoryStore.all(this)
        val cooldowns = pl.aimasterpro.app.core.ProviderCooldownStore.states(this).filter { it.until > now }
        val changeLedger = pl.aimasterpro.app.core.ChangeSetStore.readResult(this)
        val changes = changeLedger.items
        val workspaceStore = pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace"))
        val workspaceState = pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.tryWithLock {
            workspaceStore.currentInfo() to workspaceStore.snapshots()
        }
        val workspaceBusy = workspaceState == null
        val workspace = workspaceState?.first
        val snapshots = workspaceState?.second.orEmpty()
        val taskStates = pl.aimasterpro.app.core.TaskCheckpointStore.latest(this)
        val interruptedTasks = taskStates.filter { it.status in setOf("INTERRUPTED", "TIMED_OUT", "CANCELLED", "FAILED", "BLOCKED") }
        val recentFailures = events.filter { it.status.uppercase(java.util.Locale.ROOT) in setOf("FAILED", "ERROR", "BLOCKED", "CANCELLED", "TIMED_OUT", "INTERRUPTED") }.take(5)

        card("AI MASTER PRO • COMMAND CENTER", "Jeden ekran kontroli zespołu, budżetu, jakości, pamięci i bezpieczeństwa zmian. Dane operacyjne są lokalne; odczyt tego panelu nie uruchamia zapytań API.")
        card("Stan wykonania", "Status: ${if (isThinking) "PRACA W TOKU" else "GOTOWY"}\n${if (isThinking) thinkingLabel else "Brak aktywnego zadania."}\nAgenci z historią: ${agentStats.size} • katalog agentów: ${pl.aimasterpro.app.agents.AgentCatalog.all.size}\nZdarzenia dziennika: ${events.size} • ostatnie 5 awarii/blokad: ${recentFailures.size}\nCheckpointy zadań: ${taskStates.size} • przerwane/nieudane: ${interruptedTasks.size}")
        card("Budżet API", "Dzisiaj: ${budget.recordedTodayTokens} zużytych + ${budget.activeReservedTokens} zarezerwowanych / ${budget.dailyLimitTokens} tokenów\nPozostało: ${budget.remainingTokens}\nKoszt 30 dni: ${"%.4f".format(java.util.Locale.US, budget.recorded30DayCostPln)} zł / ${"%.2f".format(java.util.Locale.US, budget.monthlyCostLimitPln)} zł\nRezerwa kosztów: ${"%.4f".format(java.util.Locale.US, budget.activeReservedCostPln)} zł")
        card("Jakość i pamięć", "Oceny: ${quality.total} • pozytywne: ${quality.positive} • negatywne: ${quality.negative}\nAgenci ocenieni przez użytkownika: ${quality.ratedAgents}\nZatwierdzone wnioski jakościowe: ${quality.approved}\nPamięć operacyjna per agent: ${memories.size}/300 wpisów")
        card("Dostawcy i limity", if (cooldowns.isEmpty()) "Brak aktywnych trwałych cooldownów po HTTP 429." else cooldowns.joinToString("\n") { state ->
            val seconds = ((state.until - now).coerceAtLeast(0L) / 1000L)
            "${state.key}: ${seconds}s pozostało • 429 z rzędu: ${state.consecutive429}"
        })
        card("Kontrola zmian", "Stan rejestru: ${changeLedger.status}${if (changeLedger.status == pl.aimasterpro.app.core.ChangeSetStore.LedgerStatus.CORRUPT) " • ZAPIS ZABLOKOWANY: ${changeLedger.error}" else ""}\nRejestr: ${changes.size} • oczekujące: ${changes.count { it.status == "PROPOSED" }} • zatwierdzone: ${changes.count { it.status == "APPROVED" }} • zastosowane bez testów: ${changes.count { it.status == "APPLIED_UNVERIFIED" }} • zweryfikowane: ${changes.count { it.status == "APPLIED" }}\nTesty PASS: ${changes.count { it.testStatus == "PASS" }} • FAIL: ${changes.count { it.testStatus == "FAIL" }} • NOT_RUN: ${changes.count { it.testStatus == "NOT_RUN" }}\nPoziom PROJECT_TESTS jest wymagany do APPLIED; PASS samej integralności nie wystarcza.")
        card("Obszar roboczy projektu", if (workspaceBusy) {
            "Workspace jest zajęty operacją w tle. Odśwież Centrum po jej zakończeniu."
        } else if (workspace == null) {
            "Brak zaimportowanego projektu. Import ZIP utworzy prywatną kopię roboczą aplikacji, bez uruchamiania zawartości archiwum.\nLimit archiwum: 40 MB • limit rozpakowany: 80 MB • maks. plik: 8 MB."
        } else {
            "Projekt: ${workspace.archiveName}\nPliki: ${workspace.fileCount} • rozmiar: ${workspace.totalBytes / 1024} KB\nImport: ${java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault()).format(java.util.Date(workspace.importedAt))}\nKopie zapasowe: ${snapshots.size}/${pl.aimasterpro.app.core.ProjectWorkspaceStore.MAX_SNAPSHOTS}\nProjekt jest odizolowany w pamięci prywatnej aplikacji; oryginalne pliki i bieżąca instalacja nie są modyfikowane."
        })
        val codeIndex = workspace?.let {
            pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.tryWithLock {
                pl.aimasterpro.app.core.ProjectCodeIndex.build(workspaceStore.listFiles(3_000))
            }
        }
        card("Indeks kodu projektu", if (workspaceBusy) {
            "Indeks chwilowo niedostępny — trwa operacja na workspace."
        } else if (codeIndex == null) {
            "Indeks będzie dostępny po imporcie projektu. Zawiera wyłącznie metadane i ścieżki — nie kopiuje treści kodu do pamięci ani do API."
        } else {
            "Pliki w manifeście: ${codeIndex.totalFiles} • rozpoznane pliki źródłowe: ${codeIndex.sourceFiles}\nModuły: ${codeIndex.modules.entries.joinToString { "${it.key}: ${it.value}" }.ifBlank { "brak" }}\nJęzyki/formaty: ${codeIndex.languages.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key}: ${it.value}" }}\nDodatkowo dostępna jest ręczna analiza symboli i zależności Kotlin/Java. Analiza jest lokalna i nie wysyła kodu do API."
        })
        action("🧭 POKAŻ INDEKS I STRUKTURĘ PROJEKTU", onClick = {
            val summary = codeIndex
            val report = if (summary == null) "Brak zaimportowanego projektu." else buildString {
                appendLine("INDEKS PROJEKTU — METADANE")
                appendLine("Pliki: ${summary.totalFiles} • źródła: ${summary.sourceFiles} • ${summary.totalBytes} B")
                appendLine("\nMODUŁY")
                summary.modules.forEach { (name, count) -> appendLine("$name — $count plików") }
                appendLine("\nJĘZYKI I FORMATY")
                summary.languages.forEach { (name, count) -> appendLine("$name — $count") }
                appendLine("\nŚCIEŻKI (pierwsze 250)")
                summary.entries.take(250).forEach { appendLine("[${it.language}] [${it.module}] ${it.path} (${it.sizeBytes} B)") }
                if (summary.entries.size > 250) appendLine("… pominięto ${summary.entries.size - 250} wpisów.")
                appendLine("\nIndeks nie odczytuje ani nie wysyła treści plików.")
            }
            AlertDialog.Builder(this).setTitle("Indeks projektu").setMessage(report)
                .setPositiveButton("Kopiuj") { _, _ -> copyText(report) }.setNegativeButton("Zamknij", null).show()
        })
        action("🧩 ANALIZUJ SYMBOLE I ZALEŻNOŚCI", onClick = {
            if (codeIndex == null) {
                toast("Najpierw zaimportuj projekt ZIP.")
            } else {
                val progress = AlertDialog.Builder(this)
                    .setTitle("Analiza strukturalna")
                    .setMessage("Sprawdzanie integralności i budowanie grafu zależności…")
                    .setCancelable(false)
                    .create()
                progress.show()
                Thread {
                    val result = runCatching {
                        val report = pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                            val integrity = workspaceStore.verifyCurrentWorkspace()
                            check(integrity.valid) { "Analiza zablokowana: obszar roboczy różni się od manifestu SHA-256." }
                            pl.aimasterpro.app.core.ProjectStructureAnalyzer.analyze(workspaceStore.listFiles(3_000)) { path, limit ->
                                workspaceStore.readTextFile(path, limit)
                            }
                        }
                        buildString {
                            appendLine("ANALIZA STRUKTURALNA — LOKALNIE")
                            appendLine("Pliki przeanalizowane: ${report.analyzedFiles.size} • symbole: ${report.symbolCount} • importy: ${report.importCount}")
                            appendLine("Odczytano: ${report.totalBytesRead / 1024} KB • pominięte pliki: ${report.skippedFiles.size}")
                            appendLine("Zależności między plikami: ${report.edges.size} • importy bez dopasowania w projekcie: ${report.unresolvedImports.values.sumOf { it.size }}")
                            appendLine("Powielone typy: ${report.duplicateSymbols.size} • zależności między modułami: ${report.moduleEdges.size}")
                            appendLine("\nSYMBOLE — pierwsze 120")
                            report.analyzedFiles.flatMap { file -> file.symbols.map { file.path to it } }
                                .take(120).forEach { (path, symbol) -> appendLine("${symbol.kind} ${symbol.qualifiedName} • ${path}:${symbol.line}") }
                            appendLine("\nZALEŻNOŚCI — pierwsze 100")
                            report.edges.take(100).forEach { edge -> appendLine("${edge.from} → ${edge.to} [${edge.imports.joinToString()}]") }
                            appendLine("\nZALEŻNOŚCI MODUŁÓW")
                            report.moduleEdges.forEach { (module, targets) -> appendLine("$module → ${targets.joinToString()}") }
                            appendLine("\nPOWIELONE TYPY")
                            report.duplicateSymbols.forEach { (name, paths) -> appendLine("$name: ${paths.joinToString()}") }
                            appendLine("\nIMPORTY BEZ DOPASOWANIA W PROJEKCIE — pierwsze 80")
                            report.unresolvedImports.entries.flatMap { (path, imports) -> imports.map { path to it } }
                                .take(80).forEach { (path, imported) -> appendLine("$path: $imported") }
                            appendLine("\nPOMINIĘTE PLIKI — pierwsze 40")
                            report.skippedFiles.take(40).forEach { (path, reason) -> appendLine("$path: $reason") }
                            appendLine("\nOgraniczenia: analiza strukturalna Kotlin/Java, nie pełny kompilator ani AST. Importy zewnętrzne mogą pozostać nierozwiązane. Żadna treść kodu nie została wysłana do API.")
                        }
                    }
                    runOnUiThread {
                        if (progress.isShowing) progress.dismiss()
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        val body = result.getOrElse { "ANALIZA NIE POWIODŁA SIĘ\n${it.message ?: "Nieznany błąd"}" }
                        val view = android.widget.TextView(this).apply {
                            text = body
                            textSize = 12f
                            setTextIsSelectable(true)
                            setPadding(dp(16), dp(12), dp(16), dp(12))
                        }
                        val scroll = android.widget.ScrollView(this).apply { addView(view) }
                        AlertDialog.Builder(this).setTitle("Analiza strukturalna projektu").setView(scroll)
                            .setPositiveButton("Kopiuj raport") { _, _ -> copyText(body) }
                            .setNegativeButton("Zamknij", null).show()
                    }
                }.apply { name = "ProjectStructureAnalysis" }.start()
            }
        })
        action("🛡️ PRZYGOTUJ I ZWERYFIKUJ PATCH (UNIFIED DIFF)", onClick = {
            if (workspace == null) {
                toast("Najpierw zaimportuj projekt ZIP.")
            } else {
                val form = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(8), dp(16), dp(4))
                }
                val pathInput = field("Ścieżka względna, np. app/src/main/Foo.kt")
                val hashInput = field("Bazowy SHA-256 (puste wyłącznie dla nowego pliku)")
                val diffInput = EditText(this).apply {
                    hint = "Wklej unified diff z nagłówkami --- / +++ i sekcjami @@"
                    setTextColor(white)
                    setHintTextColor(muted)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    minLines = 8
                    maxLines = 14
                    gravity = Gravity.TOP or Gravity.START
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    background = rounded(panel, 10)
                }
                form.addView(pathInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                form.addView(hashInput, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                form.addView(diffInput, LinearLayout.LayoutParams(-1, dp(220)))
                AlertDialog.Builder(this).setTitle("Bezpieczny podgląd poprawki")
                    .setMessage("To etap PREVIEW. Nie zapisuje ani nie stosuje zmian. Dla istniejącego pliku pobierz SHA-256 z bezpiecznego podglądu pliku.")
                    .setView(form)
                    .setPositiveButton("ZWERYFIKUJ PATCH", null)
                    .setNegativeButton("Anuluj", null)
                    .create().also { dialog ->
                        dialog.setOnShowListener {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                                val path = pathInput.text.toString().trim()
                                val hash = hashInput.text.toString().trim().lowercase(java.util.Locale.ROOT)
                                val diff = diffInput.text.toString()
                                val previewChatId = currentChatId
                                val result = pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.tryWithLock {
                                    runCatching {
                                        val identity = workspaceStore.currentIdentity()
                                            ?: error("Brak poprawnej tożsamości workspace.")
                                        val integrity = workspaceStore.verifyCurrentWorkspace()
                                        check(integrity.valid) { "Workspace nie przeszedł kontroli integralności SHA-256." }
                                        val preview = pl.aimasterpro.app.core.ProjectPatchEngine.preview(
                                            listOf(pl.aimasterpro.app.core.ProjectPatchEngine.Proposal(path, hash, diff)),
                                            workspaceStore.listFiles(3_000)
                                        ) { filePath, limit -> workspaceStore.readTextFile(filePath, limit) }
                                        Triple(identity, previewChatId, preview)
                                    }
                                } ?: runCatching { error("Workspace jest zajęty operacją w tle. Spróbuj ponownie za chwilę.") }
                                val reviewPackage = result.getOrNull()?.third?.let { preview ->
                                    if (preview.accepted) pl.aimasterpro.app.core.PatchPeerReviewEngine.prepare(preview, path, diff) else null
                                }
                                val report = result.fold({ (previewIdentity, previewChat, preview) -> buildString {
                                    appendLine(if (preview.accepted) "PATCH ACCEPTED — PODGLĄD POPRAWNY" else "PATCH REJECTED — ZMIANY ZABLOKOWANE")
                                    preview.files.forEach { file ->
                                        appendLine("\n${file.operation}: ${file.path}")
                                        appendLine("SHA bazowy: ${file.baseSha256.ifBlank { "(nowy plik)" }}")
                                        appendLine("SHA wynikowy: ${file.resultSha256 ?: "(plik usuwany)"}")
                                        appendLine("Linie: ${file.oldLines} → ${file.newLines}")
                                        file.resultContent?.let { appendLine("\n--- WYNIKOWA TREŚĆ ---\n$it") }
                                    }
                                    preview.findings.forEach { appendLine("\n[${it.code}] ${it.message}") }
                                    reviewPackage?.let { review ->
                                        appendLine("\n--- WSTĘPNY AUDYT PATCHA ---")
                                        appendLine("Poziom ryzyka: ${review.riskLabel}")
                                        review.findings.forEach { appendLine("[${it.severity}] ${it.code}: ${it.message}") }
                                        appendLine("\nMożesz uruchomić dwie niezależne recenzje modeli. Wymagane są dwa różne, jawnie przypisane i zweryfikowane darmowe profile API. Recenzja nie stosuje zmian.")
                                    }
                                    appendLine("\nWorkspace SHA-256: ${previewIdentity.take(16)}…")
                                    appendLine("Czat źródłowy: ${previewChat.take(36)}")
                                    appendLine("\nBrak zapisu. Brak uruchamiania kodu. Wdrożenie wymaga osobnego etapu i jawnej zgody.")
                                } }, { "PATCH REJECTED\n${it.message ?: "Nieznany błąd"}\n\nŻaden plik nie został zmieniony." })
                                val reportView = android.widget.TextView(this).apply {
                                    text = report
                                    textSize = 12f
                                    setTextIsSelectable(true)
                                    setPadding(dp(16), dp(12), dp(16), dp(12))
                                }
                                val scroll = android.widget.ScrollView(this).apply { addView(reportView) }
                                dialog.dismiss()
                                AlertDialog.Builder(this).setTitle("Wynik walidacji patcha").setView(scroll)
                                    .setPositiveButton("Kopiuj raport") { _, _ -> copyText(report) }
                                    .apply {
                                        reviewPackage?.takeIf { it.acceptedForReview }?.let { review ->
                                            setNeutralButton("URUCHOM 2 RECENZENTÓW") { _, _ ->
                                                val (previewIdentity, previewChat, preview) = result.getOrThrow()
                                                runPatchPeerReview(review, path, preview, previewIdentity, previewChat)
                                            }
                                        }
                                    }
                                    .setNegativeButton("Zamknij", null).show()
                            }
                        }
                        dialog.show()
                    }
            }
        })
        action("📄 BEZPIECZNY PODGLĄD PLIKU ŹRÓDŁOWEGO", onClick = {
            val entries = codeIndex?.entries?.filter { it.language in setOf("Kotlin", "Kotlin DSL", "Java", "XML", "JSON", "Gradle", "Markdown", "Python", "JavaScript", "TypeScript", "TypeScript/React", "JavaScript/React", "SQL") }.orEmpty()
            if (entries.isEmpty()) {
                toast("Brak plików tekstowych do podglądu.")
            } else {
                val choices = entries.take(250).map { "${it.path}  •  ${it.sizeBytes} B" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Wybierz plik — podgląd do 24 KB")
                    .setItems(choices) { _, which ->
                        val selected = entries[which]
                        val result = runCatching {
                            pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.tryWithLock {
                                workspaceStore.readTextFile(selected.path, 24 * 1024)
                            } ?: error("Workspace jest zajęty operacją w tle. Spróbuj ponownie za chwilę.")
                        }
                        val body = result.fold(
                            { "SHA-256: ${it.sha256}\nRozmiar: ${it.content.toByteArray(Charsets.UTF_8).size} B\n\n${it.content}" },
                            { "ODCZYT ZABLOKOWANY\n${it.message ?: "Nieznany błąd"}\n\nPlik musi należeć do manifestu, mieścić się w limicie i przejść kontrolę SHA-256 oraz filtr sekretów." }
                        )
                        val view = android.widget.TextView(this).apply {
                            text = body
                            textSize = 12f
                            setTextIsSelectable(true)
                            setPadding(dp(16), dp(12), dp(16), dp(12))
                        }
                        val scroll = android.widget.ScrollView(this).apply { addView(view) }
                        AlertDialog.Builder(this).setTitle(selected.path).setView(scroll)
                            .setPositiveButton("Kopiuj") { _, _ -> if (result.isSuccess) copyText(result.getOrThrow().content) }
                            .setNegativeButton("Zamknij", null).show()
                    }.setNegativeButton("Anuluj", null).show()
            }
        })
        action("📦 IMPORTUJ PROJEKT ZIP DO OBSZARU ROBOCZEGO", onClick = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(intent, IMPORT_PROJECT_ARCHIVE)
        })
        action("Pokaż manifest plików projektu", onClick = {
            val info = pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.tryWithLock { workspaceStore.currentInfo() }
            if (info == null) { toast("Workspace jest zajęty operacją w tle lub nie ma projektu."); return@action }
            val report = buildString {
                appendLine("PROJEKT: ${info.archiveName}")
                appendLine("PLIKI: ${info.fileCount} • ${info.totalBytes} B")
                appendLine("SHA-256 manifestu plików:")
                info.files.take(300).forEach { appendLine("${it.sha256}  ${it.path}  (${it.sizeBytes} B)") }
                if (info.files.size > 300) appendLine("… pominięto ${info.files.size - 300} dalszych wpisów w widoku.")
            }
            AlertDialog.Builder(this).setTitle("Manifest obszaru roboczego").setMessage(report)
                .setPositiveButton("Kopiuj") { _, _ -> copyText(report) }.setNegativeButton("Zamknij", null).show()
        })
        action("🧪 WERYFIKUJ PATCH + AUTOMATYCZNY ROLLBACK", onClick = {
            val changeLedger = pl.aimasterpro.app.core.ChangeSetStore.readResult(this)
            if (changeLedger.status == pl.aimasterpro.app.core.ChangeSetStore.LedgerStatus.CORRUPT) {
                toast("Rejestr zmian jest uszkodzony (${changeLedger.error}). Weryfikacja i zapis zostały zablokowane.")
                return@action
            }
            val candidates = changeLedger.items.filter { it.status == "APPLIED_UNVERIFIED" && it.snapshotId.isNotBlank() }
            if (candidates.isEmpty()) {
                toast("Brak zmian APPLIED_UNVERIFIED ze snapshotem do weryfikacji.")
            } else {
                val labels = candidates.map { "${it.summary.take(90)} • ${it.paths.size} plików • snapshot ${it.snapshotId.take(18)}" }.toTypedArray()
                AlertDialog.Builder(this).setTitle("Wybierz zmianę do weryfikacji")
                    .setItems(labels) { _, which ->
                        val selected = candidates[which]
                        val progress = AlertDialog.Builder(this)
                            .setTitle("Bezpieczna weryfikacja")
                            .setMessage("Kontrola manifestów SHA-256, integralności snapshotu i dokładnego zakresu patcha. Kod projektu nie zostanie uruchomiony.")
                            .setCancelable(false).create()
                        progress.show()
                        Thread {
                            val store = pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace"))
                            val evidenceStore = pl.aimasterpro.app.core.VerificationEvidenceStore(
                                pl.aimasterpro.app.core.AndroidVerificationEvidenceBackend(applicationContext)
                            )
                            val executionRegistry = pl.aimasterpro.app.core.ToolExecutionRegistry(
                                sink = pl.aimasterpro.app.core.ToolExecutionRegistry.EvidenceSink(evidenceStore::appendAll)
                            )
                            val runner = pl.aimasterpro.app.core.ProjectTestRunner(store)
                            // Older ChangeSet records may have no chatId. Do not invent a scope id;
                            // run the legacy verification but leave it unlinked from TOOL_EXECUTION.
                            // Capture the exact workspace identity and run the verification in the
                            // same critical section. A later rollback may only restore this same
                            // workspace; it must never overwrite a project imported afterward.
                            val verificationAttempt = runCatching {
                                pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                                    val identity = store.currentIdentity()
                                        ?: error("Brak poprawnej tożsamości workspace przed weryfikacją.")
                                    val tracked = if (selected.chatId.isNotBlank()) {
                                        pl.aimasterpro.app.core.EvidenceTrackedToolAdapter(executionRegistry).execute(
                                            taskId = selected.taskId,
                                            chatId = selected.chatId,
                                            snapshotId = selected.snapshotId,
                                            kind = pl.aimasterpro.app.core.VerificationEvidenceKind.OTHER,
                                            toolName = "project.workspace.verify"
                                        ) {
                                            runner.verifyAppliedChange(selected.snapshotId, selected.paths, selected.taskId, selected.chatId)
                                        }
                                    } else null
                                    val value = tracked?.value ?: if (tracked == null) {
                                        runner.verifyAppliedChange(selected.snapshotId, selected.paths, selected.taskId, selected.chatId)
                                    } else null
                                    Triple(identity, tracked, value)
                                }
                            }
                            if (verificationAttempt.isFailure) {
                                val failureText = "Weryfikacja nie została uruchomiona: ${verificationAttempt.exceptionOrNull()?.message ?: "błąd odczytu workspace"}."
                                runOnUiThread {
                                    if (progress.isShowing) progress.dismiss()
                                    if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
                                        .setTitle("Weryfikacja zablokowana")
                                        .setMessage(failureText)
                                        .setPositiveButton("OK", null).show()
                                }
                                return@Thread
                            }
                            val verification = verificationAttempt.getOrThrow()
                            val verificationWorkspaceIdentity = verification.first
                            val trackedExecution = verification.second
                            val result = verification.third
                            if (result == null) {
                                val failureText = "Weryfikacja nie została wykonana: ${trackedExecution?.failure?.message ?: "nieznany błąd"}. " +
                                    "Wynik wykonania narzędzia: ${trackedExecution?.completion?.status ?: "NOT_TRACKED"}."
                                runOnUiThread {
                                    if (progress.isShowing) progress.dismiss()
                                    if (!isFinishing && !isDestroyed) AlertDialog.Builder(this)
                                        .setTitle("Weryfikacja przerwana")
                                        .setMessage(failureText)
                                        .setPositiveButton("OK", null).show()
                                }
                                return@Thread
                            }
                            val rollback = if (result.status == pl.aimasterpro.app.core.ProjectTestRunner.Status.FAIL) {
                                runCatching {
                                    pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                                        pl.aimasterpro.app.core.ProjectWorkspaceIdentityGuard.requireUnchanged(
                                            verificationWorkspaceIdentity, store.currentIdentity()
                                        )
                                        val restored = store.restoreSnapshot(selected.snapshotId)
                                        val scopeCleared = runCatching {
                                            pl.aimasterpro.app.core.HostActiveChangeSetStore.invalidate(this, selected.id)
                                        }.getOrDefault(false)
                                        restored to scopeCleared
                                    }
                                }
                            } else null
                            val evidencePersisted = runCatching {
                                check(evidenceStore.appendAll(result.evidence)) {
                                    "Nie udało się trwale zapisać dowodów weryfikacji."
                                }
                            }
                            val persisted = runCatching {
                                val savedStatus = when {
                                    result.status == pl.aimasterpro.app.core.ProjectTestRunner.Status.FAIL -> "FAIL"
                                    result.status == pl.aimasterpro.app.core.ProjectTestRunner.Status.PASS -> "PASS"
                                    else -> "NOT_RUN"
                                }
                                pl.aimasterpro.app.core.ChangeSetStore.recordWorkspaceVerification(
                                    this, selected.id, savedStatus, result.output
                                )
                                if (rollback?.isSuccess == true) {
                                    check(pl.aimasterpro.app.core.ChangeSetStore.transition(this, selected.id, "ROLLED_BACK")) {
                                        "Workspace przywrócono, ale nie udało się zaktualizować statusu rejestru zmian."
                                    }
                                }
                            }
                            val report = buildString {
                                append(result.output)
                                if (rollback != null) {
                                    appendLine("\nAUTOMATYCZNY ROLLBACK")
                                    if (rollback.isSuccess) {
                                        val scopeCleared = rollback.getOrThrow().second
                                        appendLine("PASS • przywrócono snapshot ${selected.snapshotId}; zmiana oznaczona jako ROLLED_BACK.")
                                        if (!scopeCleared) appendLine("UWAGA • nie potwierdzono trwałego usunięcia wskaźnika; jego niezgodność z nową tożsamością workspace blokuje użycie dowodów.")
                                    } else appendLine("FAIL • rollback zablokowany lub nieudany: ${rollback.exceptionOrNull()?.message ?: "nieznany błąd"}")
                                }
                                if (persisted.isFailure) appendLine("\nBŁĄD REJESTRU: ${persisted.exceptionOrNull()?.message ?: "nieznany błąd"}")
                                when {
                                    trackedExecution == null -> appendLine("\nWYKONANIE HOSTA: niepowiązane — historyczny rekord nie ma chatId; nie utworzono sztucznego identyfikatora.")
                                    trackedExecution.completion.status == pl.aimasterpro.app.core.ToolExecutionRegistry.CompletionStatus.PERSISTED ->
                                        appendLine("\nWYKONANIE HOSTA: zapisano executionId=${trackedExecution.executionId}; PASS oznacza wyłącznie, że weryfikator zwrócił raport, nie że testy projektu przeszły.")
                                    else -> appendLine("\nBŁĄD REJESTRU WYKONANIA: ${trackedExecution.completion.status}; pozostawiono wcześniejszy NOT_RUN.")
                                }
                                if (evidencePersisted.isSuccess) appendLine("\nDOWODY WERYFIKACJI: zapisano ${result.evidence.size} typowanych rekordów • zadanie ${selected.taskId} • czat ${selected.chatId.ifBlank { "brak danych historycznych" }} • snapshot ${selected.snapshotId}.")
                                else appendLine("\nBŁĄD DOWODÓW: ${evidencePersisted.exceptionOrNull()?.message ?: "nieznany błąd"}")
                                appendLine("\nWażne: integralność workspace to nie kompilacja. Testy projektu pozostają NOT_RUN, dopóki aplikacja nie otrzyma rzeczywistego sandboxa procesu z limitami zasobów i sieci. Zmiana nie otrzyma statusu APPLIED na podstawie samej kontroli SHA-256.")
                            }
                            runOnUiThread {
                                if (progress.isShowing) progress.dismiss()
                                if (isFinishing || isDestroyed) return@runOnUiThread
                                val view = TextView(this).apply {
                                    text = report; textSize = 12f; setTextIsSelectable(true)
                                    setPadding(dp(16), dp(12), dp(16), dp(12))
                                }
                                AlertDialog.Builder(this).setTitle("Wynik weryfikacji: ${result.status.name}")
                                    .setView(ScrollView(this).apply { addView(view) })
                                    .setPositiveButton("Kopiuj raport") { _, _ -> copyText(report) }
                                    .setNegativeButton("Zamknij", null).show()
                            }
                        }.apply { name = "ProjectSafeVerification" }.start()
                    }.setNegativeButton("Anuluj", null).show()
            }
        })
        action("🔎 SPRAWDŹ INTEGRALNOŚĆ PLIKÓW (SHA-256)", onClick = {
            val report = pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.tryWithLock {
                workspaceStore.verifyCurrentWorkspace()
            }
            if (report == null) { toast("Workspace jest zajęty operacją w tle. Spróbuj ponownie za chwilę."); return@action }
            val details = buildString {
                appendLine(if (report.valid) "WYNIK: OK — wszystkie pliki zgodne z manifestem." else "WYNIK: WYKRYTO RÓŻNICE")
                appendLine("Sprawdzone pliki: ${report.checkedFiles}")
                if (report.missing.isNotEmpty()) appendLine("Brakujące: ${report.missing.take(30).joinToString()}")
                if (report.modified.isNotEmpty()) appendLine("Zmienione: ${report.modified.take(30).joinToString()}")
                if (report.unexpected.isNotEmpty()) appendLine("Nieznane: ${report.unexpected.take(30).joinToString()}")
                appendLine("Kontrola jest lokalna; nie wysyła plików ani hashy do API.")
            }
            AlertDialog.Builder(this).setTitle("Integralność obszaru roboczego").setMessage(details)
                .setPositiveButton("Kopiuj raport") { _, _ -> copyText(details) }.setNegativeButton("Zamknij", null).show()
        })
        if (recentFailures.isNotEmpty()) {
            card("Ostatnie problemy", recentFailures.joinToString("\n\n") { e ->
                "${e.agentId} • ${e.status}\n${e.providerId}/${e.model} • ${e.category.ifBlank { "bez kategorii" }} • ${e.taskId}"
            })
        }
        action("Otwórz komunikację agentów", onClick = { navigate("Rozmowy") })
        action("Otwórz Monitor API", onClick = { navigate("Monitor API") })
        action("Otwórz diagnostykę", onClick = { navigate("Diagnostyka") })
        action("Otwórz agentów i modele", onClick = { navigate("Agenci") })
        action("Otwórz pamięć i uczenie", onClick = { showShell("Pamięć"); showMemory() })
        action("Pokaż przerwane i nieudane zadania", onClick = {
            val rows = pl.aimasterpro.app.core.TaskCheckpointStore.latest(this)
                .filter { it.status in setOf("INTERRUPTED", "TIMED_OUT", "CANCELLED", "FAILED", "BLOCKED") }.take(30)
            val body = if (rows.isEmpty()) "Brak przerwanych lub nieudanych zadań." else rows.joinToString("\n\n") {
                "${it.agentId} • ${it.status}\n${it.taskId}\n${it.detail}\n${it.providerId}/${it.model} • prób: ${it.attempts}"
            }
            AlertDialog.Builder(this).setTitle("Odzyskiwanie zadań").setMessage(body)
                .setPositiveButton("Kopiuj") { _, _ -> copyText(body) }.setNegativeButton("Zamknij", null).show()
        })
    }

    private fun addCompanionTrustCard() {
        val trustCard = card("Zaufanie Companion", "Stan zapisanego zaufania do komputera Companion. Aplikacja nigdy nie odtwarza starego zaufania automatycznie.")
        val statusView = TextView(this).apply { textSize = 13f; setTextColor(white); setPadding(0, dp(8), 0, 0) }
        trustCard.addView(statusView)
        val state = try {
            (application as CompanionApplication).companionTrustService().state()
        } catch (_: Exception) {
            null
        }
        statusView.text = when (state) {
            null -> "Nie udało się odczytać stanu zaufania. Nic nie zostało zmienione."
            CompanionTrustRecovery.State.Unpaired -> "Brak zapisanego zaufania. Parowanie nowego komputera nie jest jeszcze dostępne: wymaga transportu, który nie jest uruchomiony."
            CompanionTrustRecovery.State.PairedTlsPending -> "Zapisane zaufanie istnieje, ale certyfikat TLS nie został jeszcze wydany."
            CompanionTrustRecovery.State.Paired -> "Zapisane zaufanie i certyfikat TLS są obecne. Sam transport Companion nie jest jeszcze uruchomiony."
            is CompanionTrustRecovery.State.RepairRequired -> "Wymagana naprawa zaufania (${state.reasons.joinToString(", ")}). Aplikacja nie odtworzy go automatycznie."
        }
        action("Odzyskaj zaufanie (ponowne parowanie)…", trustCard) { confirmCompanionRecovery() }
        action("Odśwież stan zaufania", trustCard) { showShell("Diagnostyka"); showDiagnostics() }
    }

    private fun confirmCompanionRecovery() {
        val input = field("Wpisz potwierdzenie")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), 0) }
        box.addView(TextView(this).apply {
            text = "Zostaną usunięte zapisane piny zaufania, rekord PSK, łańcuch TLS i klucze TLS Companion. Stare zaufanie nie zostanie przywrócone; komputer trzeba będzie sparować od nowa. Aby potwierdzić, wpisz dokładnie: ${CompanionTrustRecovery.CONFIRMATION}"
            textSize = 13f; setTextColor(muted); setPadding(0, 0, 0, dp(10))
        })
        box.addView(input)
        AlertDialog.Builder(this).setTitle("Odzyskiwanie zaufania Companion")
            .setView(box).setNegativeButton("Anuluj", null)
            .setPositiveButton("Usuń zaufanie") { _, _ ->
                val result = try {
                    (application as CompanionApplication).companionTrustService().recover(input.text.toString())
                } catch (_: Exception) {
                    null
                }
                val message = when (result) {
                    CompanionTrustRecovery.WipeResult.Wiped -> "Zaufanie Companion usunięte. Można zacząć parowanie od nowa, gdy transport będzie dostępny."
                    is CompanionTrustRecovery.WipeResult.Refused -> "Nie potwierdzono operacji. Nic nie zostało usunięte."
                    is CompanionTrustRecovery.WipeResult.Failed -> "Odzyskiwanie przerwane na etapie ${result.step}. Ponów operację; stan zostanie sprawdzony ponownie."
                    null -> "Nie udało się wykonać odzyskiwania. Nic nie zostało zmienione."
                }
                toast(message)
                showShell("Diagnostyka"); showDiagnostics()
            }.show()
    }

    private fun showDiagnostics() {
        val usage = pl.aimasterpro.app.core.ApiTokenTracker.budgetUsageSnapshot(this)
        val used = usage.recordedTodayTokens + usage.activeReservedTokens
        val percent = (used * 100.0 / usage.dailyLimitTokens.coerceAtLeast(1L)).toInt().coerceIn(0, 999)
        card("Centrum diagnostyczne", "Szybka kontrola lokalnego budżetu, rezerwacji i konfiguracji. Klucze API nie są wyświetlane ani kopiowane.")
        addCompanionTrustCard()
        card("Smart Budget — stan dzienny", "Zużyto według API: ${usage.recordedTodayTokens} tokenów\nAktywne rezerwacje: ${usage.activeReservedTokens} tokenów\nŁącznie zajęte: $used / ${usage.dailyLimitTokens} ($percent%)\nPozostało do rezerwacji: ${usage.remainingTokens} tokenów\nLimit na jednego agenta: ${usage.perAgentLimitTokens} tokenów\nKoszt zapisany z 30 dni: ${String.format(java.util.Locale.US, "%.4f", usage.recorded30DayCostPln)} PLN\nAktywny koszt zarezerwowany: ${String.format(java.util.Locale.US, "%.4f", usage.activeReservedCostPln)} PLN\nLimit kosztów: ${if (usage.monthlyCostLimitPln > 0.0) "${usage.monthlyCostLimitPln} PLN" else "wyłączony"}")
        action("Otwórz Monitor API i limity budżetowe", onClick = {
            selectedMonitorPeriod = "Dzisiaj"
            showShell("Monitor API")
            showApiMonitoringScreen()
        })
        action("Zmień limity tokenów", onClick = { showLimitsDialog() })
        action("Kopiuj raport diagnostyczny", onClick = {
            val report = buildString {
                appendLine("AI MASTER PRO — RAPORT DIAGNOSTYCZNY")
                appendLine("Dzienny limit: ${usage.dailyLimitTokens}")
                appendLine("Zużycie zapisane: ${usage.recordedTodayTokens}")
                appendLine("Aktywne rezerwacje: ${usage.activeReservedTokens}")
                appendLine("Pozostało: ${usage.remainingTokens}")
                appendLine("Limit agenta: ${usage.perAgentLimitTokens}")
                appendLine("Koszt 30 dni: ${String.format(java.util.Locale.US, "%.4f", usage.recorded30DayCostPln)} PLN")
                appendLine("Aktywny koszt rezerwacji: ${String.format(java.util.Locale.US, "%.4f", usage.activeReservedCostPln)} PLN")
                appendLine("Limit kosztów: ${usage.monthlyCostLimitPln} PLN (0 = wyłączony)")
                appendLine("\nUwaga: darmowy model API nadal zużywa tokeny i może podlegać limitom dostawcy.")
            }
            copyText(report)
        })
        action("Sprawdź zapisane konfiguracje (bez kluczy)", onClick = {
            val ids = listOf("groq", "openrouter", "gemini", "openai-compatible")
            val report = ids.joinToString("\n\n") { id ->
                val p = loadProvider(id)
                if (p == null) "$id: brak konfiguracji" else "$id\nNazwa: ${p.name}\nAdres: ${p.baseUrl}\nModel: ${p.model}\nKlucz API: ${if (p.apiKey.isNotBlank()) "zapisany" else "BRAK"}\nTyp: ${p.kind}"
            }
            AlertDialog.Builder(this).setTitle("Raport konfiguracji").setMessage(report).setPositiveButton("Kopiuj") { _, _ -> copyText(report) }.setNegativeButton("Zamknij", null).show()
        })
        action("Wklej log błędu do analizy", onClick = {
            val input = EditText(this).apply { hint = "Wklej pełny komunikat, stack trace lub log Gradle"; minLines = 5; gravity = Gravity.TOP; setTextColor(white) }
            AlertDialog.Builder(this).setTitle("Analiza logu").setView(input).setNegativeButton("Anuluj", null).setPositiveButton("Przygotuj raport") { _, _ ->
                val log = input.text.toString().trim()
                if (log.isNotBlank()) {
                    val report = "RAPORT WSTĘPNY\n\n1. Log wejściowy:\n$log\n\n2. Nie uznajemy przyczyny za potwierdzoną, dopóki nie zostanie sprawdzona w kodzie i teście."
                    AlertDialog.Builder(this).setTitle("Raport diagnostyczny").setMessage(report).setPositiveButton("Kopiuj") { _, _ -> copyText(report) }.setNegativeButton("Zamknij", null).show()
                }
            }.show()
        })
        card("Ważne", "Darmowy model nie oznacza nielimitowanego użycia. Smart Budget nadal kontroluje dzienną pulę tokenów aplikacji, a dostawca może dodatkowo nakładać własne limity. Zwiększaj limit dzienny tylko wtedy, gdy świadomie akceptujesz większe zużycie.")
    }

    private fun showMemory() {
        pl.aimasterpro.app.core.QualityLearningStore.syncApprovedAgentMemories(this)
        val memories = pl.aimasterpro.app.core.AiMemoryStore.getMemories(this)
        val journal = pl.aimasterpro.app.core.LearningJournalStore.load(this)
        val agentEvents = journal.count { it.kind == "agent" }
        val chatEvents = journal.count { it.kind == "chat" }
        card("Pamięć i uczenie na wynikach", "To nie jest trening wag modelu. Aplikacja lokalnie zapisuje metryki, pozwala oceniać odpowiedzi 👍/👎 i porównywać agentów oraz modele. Zwykłe statystyki i oceny nie wykonują żadnych wywołań API. Analiza MASTER-a jest wyłącznie ręczna i zużywa tokeny dopiero po Twoim potwierdzeniu. Wnioski wpływają na pamięć dopiero po zatwierdzeniu.")
        card("Stan dziennika", "Przebiegi agentów: $agentEvents\nWiadomości czatu: $chatEvents\nZapisane zasady pamięci: ${memories.size}\nLimit dziennika: 500 ostatnich zdarzeń\nMiejsce zapisu: lokalne SharedPreferences aplikacji (pozostaje po restarcie, znika po wyczyszczeniu danych aplikacji).")
        val scopedMemories = pl.aimasterpro.app.core.AgentOperationalMemoryStore.all(this)
        val scopedSummary = scopedMemories.groupBy { it.agentId }.entries.sortedBy { it.key }
            .joinToString("\n") { (agent, rows) -> "$agent: ${rows.size} zatwierdzonych wniosków" }
            .ifBlank { "Brak pamięci przypisanej do specjalistów." }
        card("Pamięć operacyjna agentów", "Wpisy: ${scopedMemories.size}/300\n$scopedSummary\nPamięć jest lokalna, oddzielona per agent i dołączana tylko wtedy, gdy pasuje do zadania. Nowe wnioski trafiają tu po zatwierdzeniu.")
        val changeLedger = pl.aimasterpro.app.core.ChangeSetStore.readResult(this)
        val changeSets = changeLedger.items
        card("Kontrola zmian projektu", "Stan rejestru: ${changeLedger.status}${if (changeLedger.status == pl.aimasterpro.app.core.ChangeSetStore.LedgerStatus.CORRUPT) " • ZAPIS ZABLOKOWANY: ${changeLedger.error}" else ""}\nZarejestrowane propozycje: ${changeSets.size}\nOczekują na decyzję: ${changeSets.count { it.status == "PROPOSED" }} • zatwierdzone: ${changeSets.count { it.status == "APPROVED" }} • testy PASS: ${changeSets.count { it.testStatus == "PASS" }}\nWalidator blokuje ścieżki poza projektem, katalogi chronione i znane wzorce sekretów. Zastosowane patche trafiają wyłącznie do prywatnego workspace; oryginalne źródła i zainstalowana aplikacja pozostają nietknięte.")
        action("🧪 Historia propozycji zmian", onClick = {
            val ledger = pl.aimasterpro.app.core.ChangeSetStore.readResult(this)
            if (ledger.status == pl.aimasterpro.app.core.ChangeSetStore.LedgerStatus.CORRUPT) {
                toast("Rejestr zmian jest uszkodzony (${ledger.error}). Nie można otworzyć historii.")
                return@action
            }
            val rows = ledger.items
            if (rows.isEmpty()) { toast("Brak zarejestrowanych propozycji zmian."); return@action }
            val labels = rows.map { "${it.status} • ${it.summary.take(90)} • ${it.paths.size} plików • testy ${it.testStatus}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Rejestr zmian (${rows.size})").setItems(labels) { _, which ->
                val item = rows[which]
                val detail = "${item.summary}\n\nZadanie: ${item.taskId}\nStatus: ${item.status}\nTesty: ${item.testStatus}\nPliki:\n${item.paths.joinToString("\n")}\n\nWynik testów:\n${item.testOutput.ifBlank { "Nie zapisano wyniku testów." }}"
                AlertDialog.Builder(this).setTitle("Propozycja zmiany").setMessage(detail).setPositiveButton("OK", null).show()
            }.setNegativeButton("Zamknij", null).show()
        })
        action("🧠 Zarządzaj pamięcią agentów", onClick = {
            val entries = pl.aimasterpro.app.core.AgentOperationalMemoryStore.all(this)
            if (entries.isEmpty()) { toast("Brak zatwierdzonych wniosków przypisanych do agentów."); return@action }
            val labels = entries.map { "${it.agentId} • ${it.category.ifBlank { "ogólne" }} • ${it.text.take(100)}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Pamięć agentów (${entries.size})").setItems(labels) { _, which ->
                val entry = entries[which]
                AlertDialog.Builder(this).setTitle("${entry.agentId} • pamięć")
                    .setMessage("${entry.text}\n\nPewność: ${entry.confidence}\nŹródła: ${entry.sourceTaskIds.size}")
                    .setNeutralButton("Usuń") { _, _ ->
                        pl.aimasterpro.app.core.AgentOperationalMemoryStore.remove(this, entry.id)
                        toast("Usunięto wpis pamięci agenta."); showShell("Pamięć"); showMemory()
                    }.setPositiveButton("OK", null).show()
            }.setNegativeButton("Zamknij", null).show()
        })
        val quality = pl.aimasterpro.app.core.QualityLearningStore.summary(this)
        val positivePct = if (quality.total == 0) 0 else quality.positive * 100 / quality.total
        card("Centrum jakości i uczenia się", "Ocenione elementy: ${quality.total}\nPozytywne oceny: ${quality.positive} • negatywne: ${quality.negative} • ${positivePct}% pozytywnych\nAwarie techniczne modeli: ${quality.technicalErrors} • średnia latencja: ${quality.avgLatencyMs} ms\nPropozycje wniosków: ${quality.proposals} • zatwierdzone: ${quality.approved}\nOceny i statystyki są lokalne. Nie zużywają tokenów.")
        action("📊 Pokaż rankingi jakości agentów i modeli", onClick = {
            val rows = pl.aimasterpro.app.core.QualityLearningStore.feedback(this)
            if (rows.isEmpty()) { toast("Najpierw oceń kilka odpowiedzi. Rankingi są liczone lokalnie."); return@action }
            fun rankLines(label: String, grouped: Map<String, List<pl.aimasterpro.app.core.QualityLearningStore.Feedback>>): String =
                label + "\n" + grouped.map { (name, items) ->
                    val good = items.count { it.rating > 0 }
                    val pct = good * 100 / items.size.coerceAtLeast(1)
                    val interval = pl.aimasterpro.app.core.AgentQualityRanker.wilsonInterval(good, items.size)
                    val confidence = when { items.size >= 20 -> "większa próba"; items.size >= 10 -> "umiarkowana próba"; items.size >= 5 -> "wstępna próba"; else -> "mało danych" }
                    Triple(name, items.size, "$pct% pozytywnych • $good/${items.size} • 95% przedział ${ (interval.first * 100).toInt() }–${ (interval.second * 100).toInt() }% • $confidence")
                }.sortedWith(compareByDescending<Triple<String, Int, String>> { it.second }.thenByDescending { it.third.substringBefore('%').toIntOrNull() ?: 0 })
                    .joinToString("\n") { "${it.first}: ${it.third}" }
            val agents = rows.filter { it.targetType == "AGENT" && it.agentId.isNotBlank() }.groupBy { it.agentId }
            val models = rows.filter { it.model.isNotBlank() }.groupBy { "${it.targetType}: ${it.providerId}/${it.model}" }
            val report = rankLines("AGENTÓW (raporty specjalistów)", agents) + "\n\n" + rankLines("MODELI / PROVIDERÓW (z podziałem na typ oceny)", models) +
                "\n\nRanking pokazuje wyłącznie oceny użytkownika, nie liczbę poprawnie zakończonych zapytań. Odpowiedzi końcowe i raporty agentów są rozdzielone. Poniżej 5 niezależnych ocen nie wyciągaj wniosków. Przedział 95% pokazuje niepewność estymacji i przy małej próbie może być szeroki; oceny tej samej rozmowy nie zwiększają liczebności próby."
            AlertDialog.Builder(this).setTitle("Lokalny ranking jakości").setMessage(report)
                .setPositiveButton("Kopiuj") { _, _ -> copyText(report) }.setNegativeButton("Zamknij", null).show()
        })
        action("👍 / 👎 Oceń ostatnie odpowiedzi agentów i MASTER-a", onClick = {
            val recent = pl.aimasterpro.app.core.LearningJournalStore.load(this)
                .filter { (it.kind == "agent" || it.agentId.equals("MASTER", true)) && it.status.equals("COMPLETED", true) }
                .distinctBy { it.taskId.ifBlank { "${it.timestamp}-${it.agentId}" } }.take(30)
            if (recent.isEmpty()) { toast("Brak zapisanych przebiegów do oceny."); return@action }
            val labels = recent.map { e ->
                val targetType = if (e.agentId.equals("MASTER", true)) "MASTER" else "AGENT"
                val rated = pl.aimasterpro.app.core.QualityLearningStore.feedback(this).any {
                    it.targetId == e.taskId && it.targetType == targetType
                }
                "${if (rated) "★ " else ""}${e.agentId} • ${e.providerId}/${e.model} • ${e.status} • ${java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(e.timestamp))}"
            }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Wybierz raport do oceny").setItems(labels) { _, which ->
                val e = recent[which]
                val comment = EditText(this).apply { hint = "Komentarz (opcjonalnie): co było dobre lub błędne?"; minLines = 2; setTextColor(white) }
                AlertDialog.Builder(this).setTitle("Ocena: ${e.agentId}").setMessage("Czy ten raport rzeczywiście pomógł rozwiązać zadanie? Ocena dotyczy użyteczności treści, nie samego statusu API.")
                    .setView(comment).setNeutralButton("👎 Nie pomógł") { _, _ ->
                        pl.aimasterpro.app.core.QualityLearningStore.rate(this, e.taskId.ifBlank { "${e.timestamp}-${e.agentId}" }, if (e.agentId.equals("MASTER", true)) "MASTER" else "AGENT", -1, comment.text.toString(), e.agentId, e.providerId, e.model, e.category)
                        toast("Zapisano ocenę negatywną."); showShell("Pamięć"); showMemory()
                    }.setPositiveButton("👍 Pomógł") { _, _ ->
                        pl.aimasterpro.app.core.QualityLearningStore.rate(this, e.taskId.ifBlank { "${e.timestamp}-${e.agentId}" }, if (e.agentId.equals("MASTER", true)) "MASTER" else "AGENT", 1, comment.text.toString(), e.agentId, e.providerId, e.model, e.category)
                        toast("Zapisano ocenę pozytywną."); showShell("Pamięć"); showMemory()
                    }.setNegativeButton("Anuluj", null).show()
            }.setNegativeButton("Zamknij", null).show()
        })
        action("🧠 Przeanalizuj jakość — MASTER (zużywa tokeny)", onClick = {
            if (isThinking) { toast("Poczekaj na zakończenie bieżącego zapytania."); return@action }
            val cfg = loadProvider(agentProviders[AgentRole.MASTER.name] ?: selectedProviderId)
            if (!providerReady(cfg)) { toast("Najpierw skonfiguruj działający profil MASTER-a."); return@action }
            AlertDialog.Builder(this).setTitle("Uruchomić analizę jakości?")
                .setMessage("To jednorazowe wywołanie modelu MASTER i zużyje tokeny zgodnie z Monitor API. Statystyki lokalne i oceny nie zużywają tokenów. Analiza nie zmieni routingu automatycznie.")
                .setNegativeButton("Anuluj", null).setPositiveButton("Uruchom analizę") { _, _ ->
                    val progress = AlertDialog.Builder(this).setTitle("MASTER analizuje historię…")
                        .setMessage("Analiza ocen, modeli, agentów i błędów technicznych. Poczekaj na wynik.")
                        .setCancelable(false).create()
                    progress.show()
                    val contextData = pl.aimasterpro.app.core.QualityLearningStore.analysisContext(this)
                    val auditChatId = currentChatId
                    val auditScope = client.beginRequestScope()
                    val taskIds = pl.aimasterpro.app.core.QualityLearningStore.feedback(this).map { it.targetId }.take(100)
                    executor.execute {
                        val answer = runCatching {
                            chatWithFallback(cfg!!, AgentPrompts.systemPrompt(AgentRole.MASTER) + "\nJesteś audytorem jakości zespołu agentów. Odróżniaj jakość treści od awarii technicznych. Nie zmieniaj konfiguracji ani routingu. Proponuj tylko wnioski poparte danymi, podaj liczebność próby i nie wyciągaj mocnych wniosków poniżej 5 ocen.",
                                "Przeprowadź analizę jakości na żądanie. Wskaż mocne/słabe strony agentów i modeli, rozdziel błędy API od błędów merytorycznych, oceń przydatność raportów na podstawie ocen użytkownika, zaproponuj ostrożne reguły routingu. Każdy wniosek opatrz liczbą dowodów i pewnością.\n\n$contextData", "", "", "quality-audit-${UUID.randomUUID()}", AgentRole.MASTER.name, auditChatId, auditScope).text
                        }
                        runOnUiThread {
                            progress.dismiss()
                            if (answer.isFailure) {
                                AlertDialog.Builder(this).setTitle("Analiza nie powiodła się").setMessage(answer.exceptionOrNull()?.message ?: "Nieznany błąd").setPositiveButton("OK", null).show()
                            } else {
                                val resultText = answer.getOrNull().orEmpty()
                                AlertDialog.Builder(this).setTitle("Wnioski MASTER-a").setMessage(resultText)
                                    .setNeutralButton("Kopiuj") { _, _ -> copyText(resultText) }
                                    .setPositiveButton("Zapisz jako PROPOZYCJĘ") { _, _ ->
                                        val events = pl.aimasterpro.app.core.LearningJournalStore.load(this)
                                        val feedbackRows = pl.aimasterpro.app.core.QualityLearningStore.feedback(this)
                                        pl.aimasterpro.app.core.QualityLearningStore.addProposal(this, resultText, feedbackRows.size,
                                            if (feedbackRows.size >= 10) "wyższa" else if (feedbackRows.size >= 5) "wstępna" else "niska — mała próba",
                                            taskIds, feedbackRows.map { it.agentId }, feedbackRows.map { "${it.providerId}/${it.model}" })
                                        toast("Zapisano propozycję. Nie wpłynie na routing, dopóki jej nie zatwierdzisz.")
                                        showShell("Pamięć"); showMemory()
                                    }.setNegativeButton("Zamknij", null).show()
                            }
                        }
                    }
                }.show()
        })
        action("📋 Przeglądaj i zatwierdzaj wnioski MASTER-a", onClick = {
            val proposals = pl.aimasterpro.app.core.QualityLearningStore.proposals(this)
            if (proposals.isEmpty()) { toast("Nie ma jeszcze propozycji. Uruchom analizę MASTER-a."); return@action }
            val labels = proposals.take(40).map { "${it.status} • ${it.confidence} • ${it.evidenceCount} ocen • ${it.text.take(90).replace('\n', ' ')}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Wnioski do weryfikacji").setItems(labels) { _, which ->
                val p = proposals[which]
                AlertDialog.Builder(this).setTitle("Wniosek • ${p.status}").setMessage("${p.text}\n\nDowody: ${p.evidenceCount} • Pewność: ${p.confidence}\nZadania źródłowe: ${p.sourceTaskIds.joinToString().ifBlank { "brak" }}\nAgenci: ${p.affectedAgents.joinToString()}\nModele: ${p.affectedModels.joinToString()}")
                    .setPositiveButton("Zatwierdź i dodaj do pamięci") { _, _ ->
                        pl.aimasterpro.app.core.QualityLearningStore.setProposalStatus(this, p.id, "APPROVED")
                        toast("Zatwierdzono. Wniosek będzie dołączany do pamięci MASTER-a."); showShell("Pamięć"); showMemory()
                    }.setNegativeButton("Odrzuć") { _, _ ->
                        pl.aimasterpro.app.core.QualityLearningStore.setProposalStatus(this, p.id, "REJECTED")
                        toast("Odrzucono wniosek."); showShell("Pamięć"); showMemory()
                    }.setNeutralButton("Zamknij", null).show()
            }.setNegativeButton("Zamknij", null).show()
        })
        action("📚 Pokaż zapisane zasady pamięci", onClick = {
            AlertDialog.Builder(this).setTitle("Zasady przekazywane modelom")
                .setMessage(memories.mapIndexed { i, item -> "${i + 1}. $item" }.joinToString("\n\n"))
                .setPositiveButton("OK", null).show()
        })
        action("＋ Dodaj zatwierdzoną zasadę / wniosek", onClick = {
            val input = EditText(this).apply { hint = "Np. Przy błędzie DNS nie ponawiaj bez końca tego samego żądania."; minLines = 3; gravity = Gravity.TOP; setTextColor(white) }
            AlertDialog.Builder(this).setTitle("Dodaj do pamięci wspólnej").setView(input)
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Zapisz zasadę") { _, _ ->
                    val value = input.text.toString().trim()
                    if (value.isNotBlank()) {
                        pl.aimasterpro.app.core.AiMemoryStore.addMemory(this, value)
                        toast("Zapisano zasadę. Będzie dołączana do kolejnych zadań.")
                        showShell("Pamięć"); showMemory()
                    }
                }.show()
        })
        action("🧾 Pokaż dziennik uczenia", onClick = {
            val latest = pl.aimasterpro.app.core.LearningJournalStore.load(this).take(80)
            val fmt = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault())
            val body = if (latest.isEmpty()) "Brak zdarzeń. Zostaną zapisane po kolejnych zadaniach." else latest.joinToString("\n\n") { e ->
                "${fmt.format(java.util.Date(e.timestamp))} • ${e.kind} / ${e.agentId}\n${e.status} • ${e.providerId}/${e.model}\nCzas: ${e.durationMs} ms • próby: ${if (e.attempts > 0) e.attempts.toString() else "n/d"} • tokeny wej/wyj: ${e.inputTokens}/${e.outputTokens}${if (e.category.isBlank()) "" else " • ${e.category}"}"
            }
            AlertDialog.Builder(this).setTitle("Dziennik uczenia — ostatnie ${latest.size}").setMessage(body)
                .setPositiveButton("Kopiuj") { _, _ -> copyText(body) }.setNegativeButton("Zamknij", null).show()
        })
        action("🗑 Wyczyść dziennik uczenia", onClick = {
            AlertDialog.Builder(this).setTitle("Wyczyścić dziennik?")
                .setMessage("Usunie wyłącznie historię zdarzeń i metryki używane do oceny operacyjnej. Oceny jakości, propozycje uczenia, zatwierdzone zasady pamięci, historia czatów, modele i klucze pozostaną bez zmian.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Wyczyść") { _, _ ->
                    pl.aimasterpro.app.core.LearningJournalStore.clear(this)
                    toast("Wyczyszczono dziennik uczenia.")
                    showShell("Pamięć"); showMemory()
                }.show()
        })
        action("Jak działa samodoskonalenie?", onClick = {
            AlertDialog.Builder(this).setTitle("Uczenie pod kontrolą").setMessage(
                "1. Po każdym przebiegu zapisujemy metadane: agent, provider/model, status, czas i liczbę prób. Nie zapisujemy promptów, odpowiedzi ani kluczy API w dzienniku.\n\n" +
                "2. Z historii wyliczamy skuteczność i średni czas agentów. Te zbiorcze dane są dołączane do kontekstu kolejnych zadań. To pomaga MASTER-owi dobierać role, ale nie dowodzi jakości merytorycznej odpowiedzi.\n\n" +
                "3. Nowe reguły i wiedza projektowa są dodawane do pamięci dopiero po Twoim zatwierdzeniu.\n\n" +
                "4. Nie trenujemy wag modeli. Nie wysyłamy dziennika na zewnętrzny serwer. Dane pozostają lokalnie na urządzeniu.")
                .setPositiveButton("OK", null).show()
        })
    }

    private var selectedMonitorPeriod = "Dzisiaj"

    private fun showApiMonitoringScreen() {
        val col = content
        col.setPadding(dp(16), dp(14), dp(16), dp(24))

        val allRecords = pl.aimasterpro.app.core.ApiTokenTracker.loadAllRecordsFromStorage(this)
        val filtered = pl.aimasterpro.app.core.ApiTokenTracker.filterRecords(allRecords, selectedMonitorPeriod)
        val stats = pl.aimasterpro.app.core.ApiTokenTracker.calculateSummary(filtered)
        val realCount = filtered.count { it.status.equals("REAL", true) }
        val estimatedCount = filtered.count { it.status.equals("ESTIMATED", true) }
        val unavailableCount = filtered.count { it.status.equals("UNAVAILABLE", true) }
        val unknownCount = filtered.size - realCount - estimatedCount - unavailableCount

        val header = card("Monitor API", "Lokalny rejestr zużycia z odpowiedzi API. Dane REAL pochodzą z usage zwróconego przez dostawcę; ESTIMATED to szacunek, gdy API nie podało liczników.")
        val headerActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun smallAction(label: String, onClick: () -> Unit) {
            val button = TextView(this).apply {
                text = label
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(white)
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = ripple(panel, 10, border)
                setOnClickListener { onClick() }
            }
            headerActions.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
        }
        smallAction("↻ Odśwież") { showShell("Monitor API"); showApiMonitoringScreen() }
        smallAction("Kopiuj raport") {
            val report = buildString {
                appendLine("AI MASTER PRO — RAPORT MONITOR API")
                appendLine("Okres: $selectedMonitorPeriod")
                appendLine("Zapytania: ${stats.totalQueries}")
                appendLine("Tokeny: ${stats.totalTokens} (wejście ${stats.inputTokens}, wyjście ${stats.outputTokens})")
                appendLine("REAL: $realCount | ESTIMATED: $estimatedCount | UNAVAILABLE: $unavailableCount | inne: $unknownCount")
                appendLine("Reasoning: ${stats.reasoningTokens} | Cached: ${stats.cachedTokens}")
                appendLine("Koszt szacowany: ${'$'}${String.format(java.util.Locale.US, "%.6f", stats.costUsd)} USD / ${String.format(java.util.Locale.US, "%.4f", stats.costPln)} PLN")
                appendLine()
                filtered.take(100).forEach { r ->
                    appendLine("${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(r.timestamp))} | ${r.providerId} | ${r.modelName} | ${r.agentId} | ${r.totalTokens} tok | ${r.status}")
                }
            }
            copyText(report)
        }
        header.addView(headerActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        val periodRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        listOf("Sesja", "Dzisiaj", "7 dni", "30 dni", "Cały okres").forEach { period ->
            val selected = period == selectedMonitorPeriod
            val button = TextView(this).apply {
                text = period
                textSize = 12f
                setTextColor(if (selected) white else muted)
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(9), dp(11), dp(9))
                background = ripple(if (selected) panel else Color.TRANSPARENT, 10, if (selected) orange else border)
                setOnClickListener {
                    selectedMonitorPeriod = period
                    showShell("Monitor API")
                    showApiMonitoringScreen()
                }
            }
            periodRow.addView(button, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(5) })
        }
        col.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(periodRow)
        })

        fun statTile(parent: LinearLayout, label: String, value: String, detail: String = "") {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                background = rounded(cardBg, 13, border)
            }
            tile.addView(TextView(this).apply {
                text = label
                textSize = 11f
                setTextColor(muted)
            })
            tile.addView(TextView(this).apply {
                text = value
                textSize = 19f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(white)
                setPadding(0, dp(4), 0, 0)
            })
            if (detail.isNotBlank()) tile.addView(TextView(this).apply {
                text = detail
                textSize = 10f
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
            })
            parent.addView(tile, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6); bottomMargin = dp(7) })
        }
        val totalsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statTile(totalsRow, "Zapytania", stats.totalQueries.toString(), "w wybranym okresie")
        statTile(totalsRow, "Tokeny łącznie", String.format(java.util.Locale.US, "%,d", stats.totalTokens), "In ${stats.inputTokens} • Out ${stats.outputTokens}")
        col.addView(totalsRow)
        val costRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        statTile(costRow, "Koszt szacowany", "${String.format(java.util.Locale.US, "%.2f", stats.costPln)} zł", "${'$'}${String.format(java.util.Locale.US, "%.4f", stats.costUsd)} USD")
        statTile(costRow, "Dane REAL", realCount.toString(), "ESTIMATED: $estimatedCount")
        col.addView(costRow)

        val qualityCard = card("Jakość danych", "REAL = liczniki zwrócone przez API. ESTIMATED = przybliżenie z długości tekstu. UNAVAILABLE = brak wiarygodnego pomiaru.")
        qualityCard.addView(TextView(this).apply {
            text = "REAL: $realCount     ESTIMATED: $estimatedCount     UNAVAILABLE: $unavailableCount     Inne: $unknownCount"
            textSize = 12f
            setTextColor(white)
            setPadding(0, dp(5), 0, 0)
        })
        if (filtered.isEmpty()) {
            val emptyCard = card("Brak danych w tym okresie", "Monitor zapisuje zużycie po udanym zapytaniu modelu i po testach modeli. Wykonaj test połączenia lub wyślij wiadomość, a następnie wybierz Odśwież.")
            emptyCard.addView(TextView(this).apply {
                text = "Uwaga: dostawcy nie zawsze zwracają usage. W takich przypadkach wpis będzie oznaczony jako ESTIMATED, a nie REAL."
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(5), 0, 0)
            })
        }

        fun addBreakdown(title: String, keyOf: (pl.aimasterpro.app.core.TokenRecord) -> String) {
            val groups = filtered.groupBy(keyOf).mapValues { pl.aimasterpro.app.core.ApiTokenTracker.calculateSummary(it.value) }
            val breakdown = card(title, if (groups.isEmpty()) "Brak danych w tym okresie." else "Posortowano według łącznego zużycia tokenów.")
            groups.entries.sortedByDescending { it.value.totalTokens }.take(12).forEach { (name, summary) ->
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(7), 0, dp(7))
                }
                line.addView(TextView(this).apply {
                    text = name.ifBlank { "Nieznany" }
                    textSize = 12f
                    setTextColor(white)
                    maxLines = 2
                }, LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(TextView(this).apply {
                    text = "${String.format(java.util.Locale.US, "%,d", summary.totalTokens)} tok\n${summary.totalQueries} zapyt. • ${String.format(java.util.Locale.US, "%.2f", summary.costPln)} zł"
                    textSize = 11f
                    setTextColor(muted)
                    gravity = Gravity.END
                })
                breakdown.addView(line)
                val divider = View(this).apply { setBackgroundColor(border) }
                breakdown.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        addBreakdown("Zużycie według dostawców", { it.providerId })
        addBreakdown("Zużycie według agentów", { it.agentId })
        addBreakdown("Zużycie według modeli", { it.modelName })

        val healthEvents = pl.aimasterpro.app.core.ModelHealthTracker.load(this)
        val healthCard = card("Kondycja modeli", "Lokalna historia sukcesów, błędów i czasu odpowiedzi. Na jej podstawie routing może preferować stabilniejsze modele; nie jest to odczyt limitów dostawcy.")
        if (healthEvents.isEmpty()) {
            healthCard.addView(TextView(this).apply { text = "Brak historii kondycji. Dane pojawią się po kolejnych zapytaniach."; setTextColor(muted); textSize = 12f })
        } else {
            healthEvents.groupBy { "${it.providerId} / ${it.model}" }.entries
                .sortedByDescending { it.value.maxOfOrNull { e -> e.timestamp } ?: 0L }.take(10).forEach { (name, events) ->
                    val ok = events.count { it.success }
                    val failed = events.size - ok
                    val measured = events.filter { it.latencyMs > 0L }
                    val avgMs = if (measured.isEmpty()) 0L else measured.sumOf { it.latencyMs } / measured.size
                    healthCard.addView(TextView(this).apply {
                        text = "$name\nSukcesy: $ok • Błędy: $failed • Śr. czas: ${if (avgMs > 0) "${avgMs} ms" else "brak danych"}"
                        textSize = 11.5f; setTextColor(white); setPadding(0, dp(7), 0, dp(7))
                    })
                }
        }

        val efficiencyStats = pl.aimasterpro.app.core.AgentEfficiencyTracker.stats(this)
        val efficiencyCard = card("Efektywność agentów", "Rzeczywiste metryki wykonania: liczba zadań, skuteczność zakończenia, czas i liczba prób. Nie oceniają merytorycznej jakości raportu.")
        if (efficiencyStats.isEmpty()) {
            efficiencyCard.addView(TextView(this).apply { text = "Brak zakończonych przebiegów zespołu w historii."; setTextColor(muted); textSize = 12f })
        } else {
            efficiencyStats.take(12).forEach { a ->
                val rate = if (a.tasks == 0) 0 else (a.completed * 100.0 / a.tasks).toInt()
                val agentTokens = allRecords.filter { it.agentId.equals(a.agentId, true) }.sumOf { it.totalTokens.coerceAtLeast(0L) }
                efficiencyCard.addView(TextView(this).apply {
                    text = "${a.agentId}\nZadania: ${a.tasks} • Ukończone: $rate% • Śr. czas: ${a.avgDurationMs / 1000.0}s\nPróby/zadanie: ${String.format(java.util.Locale.US, "%.1f", a.avgAttempts)} • Tokeny w wybranym okresie: $agentTokens"
                    textSize = 11.5f; setTextColor(white); setPadding(0, dp(7), 0, dp(7))
                })
            }
        }

        val limits = pl.aimasterpro.app.core.ApiTokenTracker.getLimits(this)
        val today = pl.aimasterpro.app.core.ApiTokenTracker.filterRecords(allRecords, "Dzisiaj")
        val todayTokens = today.sumOf { it.totalTokens.coerceAtLeast(0L) }
        val monthRecords = pl.aimasterpro.app.core.ApiTokenTracker.filterRecords(allRecords, "30 dni")
        val monthCost = monthRecords.sumOf { it.costPln.coerceAtLeast(0.0) }
        val activeReservations = pl.aimasterpro.app.core.ApiTokenTracker.activeReservationStats()
        val limitCard = card("Limity i wykorzystanie", "Dzienny limit: ${limits.dailyTokenLimit} tokenów\nWykorzystano dzisiaj: $todayTokens (${(todayTokens * 100.0 / limits.dailyTokenLimit.coerceAtLeast(1L)).toInt().coerceIn(0, 999)}%)\nAktywne rezerwacje: ${activeReservations.tokens} tokenów / ${activeReservations.tasks} zadań\nMiesięczny limit kosztów: ${if (limits.monthlyCostPlnLimit > 0.0) "${limits.monthlyCostPlnLimit} PLN" else "wyłączony"}\nKoszt z ostatnich 30 dni: ${String.format(java.util.Locale.US, "%.4f", monthCost)} PLN\nLimit dzienny na agenta: ${limits.agentTokenLimit} tokenów")
        action("⚙️ Skonfiguruj limity", limitCard) { showLimitsDialog() }

        val historyCard = card("Historia zapytań (${filtered.size})", "Najnowsze wpisy na początku. Dotknij wpisu, aby zobaczyć szczegóły.")
        if (filtered.isEmpty()) {
            historyCard.addView(TextView(this).apply { text = "Brak zapisanych zapytań."; setTextColor(muted); setPadding(0, dp(5), 0, 0) })
        } else {
            filtered.take(50).forEach { record ->
                val timestamp = java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(record.timestamp))
                val row = TextView(this).apply {
                    text = "$timestamp  •  ${record.agentId}\n${record.providerId} / ${record.modelName}\n${String.format(java.util.Locale.US, "%,d", record.totalTokens)} tokenów  •  ${record.status}  •  ~${String.format(java.util.Locale.US, "%.4f", record.costPln)} zł"
                    textSize = 11.5f
                    setTextColor(white)
                    setPadding(dp(4), dp(9), dp(4), dp(9))
                    setTextIsSelectable(true)
                    setOnClickListener {
                        val details = "Data: $timestamp\nDostawca: ${record.providerId}\nModel: ${record.modelName}\nAgent: ${record.agentId}\nZadanie: ${record.taskId}\nWejście: ${record.inputTokens}\nWyjście: ${record.outputTokens}\nReasoning: ${record.reasoningTokens}\nCached: ${record.cachedTokens}\nRazem: ${record.totalTokens}\nStatus pomiaru: ${record.status}\nKoszt szacowany: ${record.costUsd} USD / ${record.costPln} PLN"
                        AlertDialog.Builder(this@MainActivity).setTitle("Szczegóły zapytania").setMessage(details)
                            .setPositiveButton("Kopiuj") { _, _ -> copyText(details) }
                            .setNegativeButton("Zamknij", null).show()
                    }
                }
                historyCard.addView(row)
                historyCard.addView(View(this).apply { setBackgroundColor(border) }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
        action("Kopiuj historię okresu", historyCard) {
            val report = filtered.joinToString("\n") { "${it.timestamp}|${it.providerId}|${it.modelName}|${it.agentId}|${it.inputTokens}|${it.outputTokens}|${it.status}|${it.costPln}" }
            copyText(report.ifBlank { "Brak danych" })
        }
        action("Wyczyść historię Monitor API", historyCard) {
            AlertDialog.Builder(this).setTitle("Wyczyścić historię zużycia?")
                .setMessage("Usunięte zostaną lokalne wpisy tokenów, historia kondycji modeli i metryki efektywności agentów. Nie zmieni to kluczy API, modeli ani historii rozmów.")
                .setNegativeButton("Anuluj", null)
                .setPositiveButton("Wyczyść") { _, _ ->
                    Thread {
                        val usageCleared = pl.aimasterpro.app.core.ApiTokenTracker.clearRecords(this@MainActivity)
                        if (usageCleared) {
                            pl.aimasterpro.app.core.ModelHealthTracker.clear(this@MainActivity)
                            pl.aimasterpro.app.core.AgentEfficiencyTracker.clear(this@MainActivity)
                        }
                        runOnUiThread {
                            toast(
                                if (usageCleared) "Wyczyszczono historię Monitor API i metryki modeli/agentów."
                                else "Nie potwierdzono trwałego usunięcia historii użycia API. Dane i pozostałe metryki zachowano; spróbuj ponownie."
                            )
                            showShell("Monitor API")
                            showApiMonitoringScreen()
                        }
                    }.start()
                }.show()
        }
    }

    private fun showLimitsDialog() {
        val limits = pl.aimasterpro.app.core.ApiTokenTracker.getLimits(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val dailyInput = field("Dzienny limit tokenów", limits.dailyTokenLimit.toString(), InputType.TYPE_CLASS_NUMBER)
        val monthlyInput = field("Miesięczny limit kosztów (PLN)", limits.monthlyCostPlnLimit.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val agentInput = field("Dzienny limit per agent", limits.agentTokenLimit.toString(), InputType.TYPE_CLASS_NUMBER)
        listOf(TextView(this).apply { text = "Dzienny limit tokenów"; setTextColor(white) }, dailyInput,
               TextView(this).apply { text = "Miesięczny limit kosztów (PLN)"; setTextColor(white); setPadding(0, dp(8), 0, 0) }, monthlyInput,
               TextView(this).apply { text = "Dzienny limit na agenta"; setTextColor(white); setPadding(0, dp(8), 0, 0) }, agentInput).forEach { box.addView(it) }

        AlertDialog.Builder(this).setTitle("Konfiguracja limitów API").setView(box)
            .setNegativeButton("Anuluj", null)
            .setPositiveButton("Zapisz") { _, _ ->
                val daily = dailyInput.text.toString().toLongOrNull() ?: limits.dailyTokenLimit
                val monthly = monthlyInput.text.toString().toDoubleOrNull() ?: limits.monthlyCostPlnLimit
                val perAgent = agentInput.text.toString().toLongOrNull() ?: limits.agentTokenLimit
                pl.aimasterpro.app.core.ApiTokenTracker.setLimits(this, daily, monthly, perAgent)
                toast("Zapisano limity budżetowe.")
                showShell("Monitor API"); showApiMonitoringScreen()
            }.show()
    }

    private fun showHelp() {
        val catalogCard = card("Katalog modeli AI", "Nie wpisuj ręcznie starych nazw modeli. Otwórz zakładkę MODELE, pobierz aktualne listy z API dostawców, sprawdź ceny i utwórz profile modeli. Identyfikatory można kopiować bezpośrednio z katalogu.")
        action("OTWÓRZ KATALOG MODELI", catalogCard) { navigate("Modele") }
        val agentsCard = card("Przypisywanie modeli agentom", "Po utworzeniu profilu modelu przejdź do zakładki AGENTY. Wybierz profil przy danej roli, zapisz przypisanie i wykonaj test połączenia. Błąd 401 oznacza problem z autoryzacją, a 429 — limit lub quota.")
        action("OTWÓRZ AGENTÓW", agentsCard) { navigate("Agenci") }
        val archCard = card("Kontekst projektu", "MASTER i agenci mogą korzystać z dołączonego kontekstu architektury oraz kodu aplikacji. Raport powinien rozróżniać analizę statyczną od rzeczywistego uruchomienia lub testu.")
        action("Kopiuj kontekst architektury i kodu", archCard) {
            copyText(pl.aimasterpro.app.core.ProjectCodebase.fullContext())
        }
    }

    private fun chooseBackupToImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"
        }
        startActivityForResult(intent, OPEN_BACKUP)
    }

    private fun promptBackupPassword(export: Boolean, uri: Uri? = null, source: String? = null) {
        val input = EditText(this).apply {
            hint = if (export) "Utwórz hasło (min. 8 znaków)" else "Hasło użyte przy eksporcie"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(white); setHintTextColor(muted)
        }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 8, 22, 0) }
        box.addView(TextView(this).apply {
            text = if (export) "Plik będzie zaszyfrowany. Bez hasła nie da się odzyskać kluczy API. Zachowaj hasło w bezpiecznym miejscu." else "Wczytanie zastąpi zapisane konfiguracje dostawców na tym urządzeniu. Upewnij się, że wybrano właściwą kopię."
            setTextColor(muted); textSize = 13f; setPadding(0, 0, 0, 10)
        })
        box.addView(input)
        AlertDialog.Builder(this).setTitle(if (export) "Eksport zaszyfrowanej bazy" else "Odszyfruj bazę")
            .setView(box).setNegativeButton("Anuluj", null).setPositiveButton(if (export) "Dalej" else "Wczytaj") { _, _ ->
                val password = input.text.toString()
                if (password.length < 8) { toast("Hasło musi mieć co najmniej 8 znaków."); return@setPositiveButton }
                if (export) {
                    val configs = JSONArray()
                    store.allConfigs().forEach { configs.put(it) }
                    if (configs.length() == 0) { toast("Brak zapisanych konfiguracji do eksportu."); return@setPositiveButton }
                    val payload = JSONObject().put("app", "AI_MASTER_PRO_ANDROID").put("schemaVersion", 1).put("providers", configs).toString()
                    pendingBackup = BackupCrypto.encrypt(payload, password.toCharArray())
                    val create = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, "ai-master-pro-baza-zaszyfrowana.json")
                    }
                    startActivityForResult(create, CREATE_BACKUP)
                } else {
                    val raw = source ?: runCatching { contentResolver.openInputStream(uri!!)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    if (raw.isNullOrBlank()) { toast("Nie udało się odczytać pliku kopii."); return@setPositiveButton }
                    val result = runCatching { BackupCrypto.decrypt(raw, password.toCharArray()) }
                    if (result.isFailure) { toast("Nie można odszyfrować bazy. Sprawdź hasło i plik."); return@setPositiveButton }
                    runCatching {
                        val data = JSONObject(result.getOrThrow())
                        require(data.optString("app") == "AI_MASTER_PRO_ANDROID") { "To nie jest baza tej aplikacji." }
                        val arr = data.getJSONArray("providers")
                        val configs = (0 until arr.length()).map { arr.getJSONObject(it) }
                        require(configs.isNotEmpty()) { "Kopia nie zawiera konfiguracji." }
                        require(store.replaceAllConfigs(configs)) { "Nie udało się zapisać wszystkich konfiguracji. Część danych mogła zostać zaktualizowana; sprawdź listę dostawców i ponów import." }
                        selectedProviderId = configs.first().optString("id", "gemini")
                        showShell("Modele"); showProviders()
                        toast("Wczytano ${configs.size} konfiguracji modeli wraz z kluczami API.")
                    }.onFailure { toast("Błąd bazy: ${it.message ?: "nieprawidłowy format"}") }
                }
            }.show()
    }

    @Deprecated("Using document picker for compatibility with supported Android versions")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return
        val uri = data.data!!
        when (requestCode) {
            IMPORT_PROJECT_ARCHIVE -> {
                val uriForImport = uri
                val archiveName = runCatching {
                    contentResolver.query(uriForImport, null, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else null
                    }
                }.getOrNull() ?: "projekt.zip"
                if (!archiveName.endsWith(".zip", true)) {
                    toast("Wybierz archiwum ZIP projektu.")
                } else {
                    toast("Importuję projekt do odizolowanego obszaru roboczego…")
                    Thread {
                        val result = runCatching {
                            val stream = contentResolver.openInputStream(uriForImport) ?: error("Nie można otworzyć archiwum.")
                            pl.aimasterpro.app.core.ProjectWorkspaceOperationLock.withLock {
                                val info = stream.use {
                                    pl.aimasterpro.app.core.ProjectWorkspaceStore(File(filesDir, "project_workspace")).importZip(it, archiveName)
                                }
                                // Keep the workspace swap and evidence-scope invalidation in one
                                // process-wide critical section. Otherwise a patch could activate
                                // a fresh scope in the gap and have this import clear it afterward.
                                val scopeCleared = runCatching {
                                    pl.aimasterpro.app.core.HostActiveChangeSetStore.invalidate(applicationContext)
                                }.getOrDefault(false)
                                info to scopeCleared
                            }
                        }
                        runOnUiThread {
                            if (isFinishing || isDestroyed) return@runOnUiThread
                            if (result.isSuccess) {
                                val (info, scopeCleared) = result.getOrThrow()
                                toast(if (scopeCleared) {
                                    "Import zakończony: ${info.fileCount} plików, ${info.totalBytes / 1024} KB. Aktywny zakres dowodów unieważniono."
                                } else {
                                    "Import zakończony, ale nie udało się potwierdzić usunięcia starego zakresu dowodów. Nie używaj go; wymagany jest przegląd rejestru."
                                })
                            } else {
                                toast("Import odrzucony: ${result.exceptionOrNull()?.message ?: "błąd archiwum"}")
                            }
                            if (screen == "Centrum") { showShell("Centrum"); showCommandCenter() }
                        }
                    }.apply { name = "ProjectZipImport" }.start()
                }
            }
            CREATE_BACKUP -> {
                val ok = runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingBackup) } ?: error("Nie można zapisać pliku") }.isSuccess
                pendingBackup = ""
                toast(if (ok) "Zapisano zaszyfrowaną bazę. Przechowuj hasło osobno." else "Nie udało się zapisać kopii.")
            }
            EXPORT_CHAT_FILE -> {
                val ok = runCatching { contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(pendingExportText) } ?: error("Nie można zapisać pliku") }.isSuccess
                pendingExportText = ""
                toast(if (ok) "Eksportowano rozmowę do pliku Markdown." else "Nie udało się zapisać pliku.")
            }
            PICK_ATTACHMENT -> {
                val name = runCatching { contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME).coerceAtLeast(0)) else null } }.getOrNull() ?: (uri.lastPathSegment ?: "plik")
                val mime = contentResolver.getType(uri).orEmpty()
                val isText = mime.startsWith("text/") || name.endsWith(".log", true) || name.endsWith(".kt", true) || name.endsWith(".txt", true) || name.endsWith(".md", true) || name.endsWith(".json", true) || name.endsWith(".xml", true) || name.endsWith(".gradle", true) || name.endsWith(".kts", true) || name.endsWith(".csv", true)

                if (isText) {
                    val raw = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    if (raw == null) toast("Nie udało się odczytać pliku.") else if (raw.length > 150_000) toast("Plik jest za duży. Limit tekstu załącznika to 150 000 znaków.") else { attachedName = name; attachedText = raw; attachedMime = mime.ifBlank { "text/plain" }; showShell("Czat"); showChat(); toast("Dołączono plik tekstowy: $name.") }
                } else {
                    val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                    if (bytes == null) toast("Nie udało się odczytać załącznika.") else if (bytes.size > 15 * 1024 * 1024) toast("Plik przekracza limit 15 MB.") else {
                        attachedName = name
                        attachedMime = when {
                            mime.isNotBlank() -> mime
                            name.endsWith(".pdf", true) -> "application/pdf"
                            name.endsWith(".zip", true) -> "application/zip"
                            name.endsWith(".png", true) -> "image/png"
                            name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
                            else -> "application/octet-stream"
                        }
                        attachedBase64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                        attachedText = ""
                        showShell("Czat")
                        showChat()
                        toast("Dołączono załącznik: $name (${bytes.size / 1024} KB).")
                    }
                }
            }
            OPEN_BACKUP -> {
                val raw = runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                if (raw.isNullOrBlank()) toast("Nie udało się odczytać pliku.") else {
                    val legacy = runCatching { JSONObject(raw) }.getOrNull()
                    if (legacy != null && (legacy.optString("format") == "ai_master_pro_backup" || legacy.has("providers") && legacy.has("version"))) {
                        importLegacyBackup(legacy)
                    } else promptBackupPassword(export = false, uri = uri, source = raw)
                }
            }
        }
    }

    /** Imports old unencrypted JSON locally. No key is sent anywhere during migration. */
    private fun importLegacyBackup(data: JSONObject) {
        val arr = data.optJSONArray("providers")
        if (arr == null || arr.length() == 0) { toast("Stara baza nie zawiera profili dostawców."); return }
        val migrated = mutableListOf<JSONObject>()
        for (i in 0 until arr.length()) {
            val old = arr.optJSONObject(i) ?: continue
            val id = old.optString("id", "legacy_$i")
            val name = old.optString("name", id)
            val oldType = old.optString("type", "OPENAI_COMPATIBLE")
            val kind = when {
                oldType.equals("GEMINI", true) || id == "gemini" -> ProviderKind.GEMINI
                oldType.equals("ANTHROPIC", true) || id == "anthropic" -> ProviderKind.ANTHROPIC
                else -> ProviderKind.OPENAI_COMPATIBLE
            }
            val rawKey = old.optString("apiKey", old.optString("apiKeyEncrypted", ""))
            // Old exports sometimes used an opaque encrypted payload. Never treat it as a usable API key.
            val key = if (rawKey.startsWith("AQ.") || rawKey.startsWith("ENC:") || rawKey.startsWith("encrypted:")) "" else rawKey
            val base = old.optString("baseUrl").ifBlank {
                when (id) {
                    "gemini" -> "https://generativelanguage.googleapis.com/v1beta"
                    "anthropic" -> "https://api.anthropic.com/v1"
                    "groq" -> "https://api.groq.com/openai/v1"
                    "openai" -> "https://api.openai.com/v1"
                    "openrouter" -> "https://openrouter.ai/api/v1"
                    "perplexity" -> "https://api.perplexity.ai"
                    else -> ""
                }
            }.let { if (it == "https://openrouter.ai") "https://openrouter.ai/api/v1" else it }
            var model = old.optString("model", old.optString("modelName", ""))
            // Do not replace stale/placeholder IDs with guessed model names. Leave the model blank
            // so the user can select a current ID from the live MODELE catalog.
            model = when {
                id == "gemini" && model in listOf("", "gemini-1.5-flash", "gemini-3.5-flash-lite", "gemini-3.8-flash") -> ""
                id == "openrouter" && model in listOf("", "nvidia/nemotron-3-ultra-550b-a55b:free") -> ""
                id == "mistral" && model in listOf("", "labs-leanstral-1-5-1") -> ""
                id == "perplexity" && model in listOf("", "openai/gpt-5.5") -> ""
                id == "openai" && model in listOf("", "gpt-6-luna") -> ""
                else -> model
            }
            migrated.add(JSONObject().put("id", id).put("name", name).put("kind", kind.name).put("baseUrl", base)
                .put("model", model).put("apiKey", key).put("enabled", old.optBoolean("isEnabled", true)))
        }
        if (migrated.isEmpty()) { toast("Nie znaleziono poprawnych profili."); return }
        AlertDialog.Builder(this).setTitle("Import starej bazy")
            .setMessage("Znaleziono ${migrated.size} profili. Import odbywa się lokalnie. Profile z nieczytelnym, zaszyfrowanym polem klucza zostaną przeniesione bez klucza i będzie trzeba wpisać go ponownie. Zastąpić obecną konfigurację?")
            .setNegativeButton("Anuluj", null).setPositiveButton("Importuj") { _, _ ->
                if (!store.replaceAllConfigs(migrated)) {
                    toast("Nie udało się zapisać wszystkich konfiguracji. Część danych mogła zostać zaktualizowana; sprawdź listę dostawców i ponów import.")
                    return@setPositiveButton
                }
                modelCatalogPrefs.edit().remove("models_json").remove("last_sync").putLong("last_attempt", 0L).apply()
                seedMissingDefaultProviders()
                selectedProviderId = migrated.firstOrNull { it.optString("id") == "gemini" }?.optString("id") ?: migrated.first().optString("id")
                showShell("Modele"); showProviders()
                toast("Zaimportowano ${migrated.size} profili. Sprawdź pola kluczy API przed użyciem.")
            }.show()
    }

    private fun seedMissingDefaultProviders() {
        val existing = store.allConfigs().map { it.optString("id") }.toSet()
        val defaults = listOf(
            ProviderConfig("gemini", "Google Gemini API", ProviderKind.GEMINI, "https://generativelanguage.googleapis.com/v1beta", "", "", true),
            ProviderConfig("openai", "OpenAI API", ProviderKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "", "", true),
            ProviderConfig("anthropic", "Anthropic API", ProviderKind.ANTHROPIC, "https://api.anthropic.com/v1", "", "", true),
            ProviderConfig("groq", "Groq API", ProviderKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "", "", true),
            ProviderConfig("openrouter", "OpenRouter API", ProviderKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "", "", true),
            ProviderConfig("mistral", "Mistral AI", ProviderKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1", "", "", true),
            ProviderConfig("perplexity", "Perplexity API", ProviderKind.OPENAI_COMPATIBLE, "https://api.perplexity.ai", "", "", true),
            ProviderConfig("ollama", "Lokalny Ollama • adres komputera do ustawienia", ProviderKind.OPENAI_COMPATIBLE, "", "", "", false),
            ProviderConfig("custom", "Własny endpoint (OpenAI-compatible)", ProviderKind.OPENAI_COMPATIBLE, "", "", "", false)
        )
        defaults.filter { it.id !in existing }.forEach { saveProvider(it) }
    }

    private fun copyText(text: String, silent: Boolean = false) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("AI Master Pro", text))
        if (!silent) Toast.makeText(this, "Skopiowano do schowka.", Toast.LENGTH_SHORT).show()
    }

    private fun hideKeyboard(view: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        pulse?.cancel()
        executor.shutdownNow()
        super.onDestroy()
    }
}
