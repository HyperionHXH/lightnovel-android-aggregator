package io.github.jiangyuyi.lightnovel.desktop

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.awt.image.BufferedImage
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JCheckBox
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.UIManager
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.JProgressBar
import javax.swing.Scrollable
import javax.swing.text.html.HTMLEditorKit
import kotlin.math.roundToInt

private const val DISCOVER_CARD = "discover"
private const val SEARCH_CARD = "search"
private const val BOOKSHELF_CARD = "bookshelf"
private const val HISTORY_CARD = "history"
private const val ACCOUNT_CARD = "account"
private const val SETTINGS_CARD = "settings"
private const val PAGE_SIZE = 20

fun main() {
    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    UIManager.put("Panel.background", MIXN_BG)
    UIManager.put("TextField.background", Color.WHITE)
    UIManager.put("TextArea.background", Color.WHITE)
    UIManager.put("Button.background", Color.WHITE)
    UIManager.put("Button.foreground", MIXN_TEXT)
    UIManager.put("Button.select", MIXN_PRIMARY_CONTAINER)
    UIManager.put("Button.border", BorderFactory.createLineBorder(MIXN_BORDER))
    UIManager.put("Button.focus", MIXN_BG)
    UIManager.put("TextField.foreground", MIXN_TEXT)
    UIManager.put("TextField.caretForeground", MIXN_PRIMARY)
    UIManager.put("TextField.border", BorderFactory.createCompoundBorder(
        BorderFactory.createLineBorder(MIXN_BORDER),
        BorderFactory.createEmptyBorder(6, 8, 6, 8),
    ))
    UIManager.put("Label.font", Font("Microsoft YaHei UI", Font.PLAIN, 14))
    UIManager.put("Button.font", Font("Microsoft YaHei UI", Font.PLAIN, 14))
    val sessionStore = DesktopSessionStore()
    val registry = DesktopSourceRegistry(
        listOf(
            LightNovelKingdomDesktopSource(sessionStore = sessionStore),
            LightNovelShelfDesktopSource(sessionStore = sessionStore),
        ),
    )
    val state = DesktopRuntimeState(registry)
    SwingUtilities.invokeLater { MixnDesktopWindow(state).isVisible = true }
}

private class DesktopRuntimeState(
    val registry: DesktopSourceRegistry,
    val progress: DesktopReadingProgressStore = DesktopReadingProgressStore(),
    val preferences: DesktopPreferencesStore = DesktopPreferencesStore(),
    val offline: DesktopOfflineStore = DesktopOfflineStore(),
) {
    /** Shared source fan-out pool; per-search pools caused avoidable thread churn. */
    val sourceExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "mixn-source-worker").apply { isDaemon = true }
    }

    fun close() {
        sourceExecutor.shutdownNow()
        DesktopCoverImages.shutdown()
    }
}

private class MixnDesktopWindow(
    private val state: DesktopRuntimeState,
) : JFrame("Mixn") {
    private val content = JPanel(java.awt.CardLayout())
    private val navigationButtons = mutableMapOf<String, JButton>()
    private val cardRefreshers = mutableMapOf<String, () -> Unit>()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(event: WindowEvent?) { state.close() }
        })
        minimumSize = Dimension(1024, 680)
        size = Dimension(1240, 820)
        setLocationByPlatform(true)
        background = MIXN_BG
        content.background = MIXN_BG
        content.add(DiscoverPanel(state, ::openBook) { showCard(SEARCH_CARD) }, DISCOVER_CARD)
        content.add(SearchPanel(state, ::openBook) { showCard(DISCOVER_CARD) }, SEARCH_CARD)
        val bookshelfPanel = BookshelfPanel(state, ::openBook)
        val historyPanel = HistoryPanel(state, ::openBook)
        content.add(bookshelfPanel, BOOKSHELF_CARD)
        content.add(historyPanel, HISTORY_CARD)
        content.add(AccountPanel(state, { showCard(HISTORY_CARD) }, { showCard(SETTINGS_CARD) }), ACCOUNT_CARD)
        content.add(SettingsPanel(state), SETTINGS_CARD)
        cardRefreshers[BOOKSHELF_CARD] = bookshelfPanel::refreshIfNeeded
        cardRefreshers[HISTORY_CARD] = historyPanel::refreshIfNeeded
        add(NavigationPanel(), BorderLayout.WEST)
        add(content, BorderLayout.CENTER)
        showCard(DISCOVER_CARD)
    }

    private fun showCard(name: String) {
        (content.layout as java.awt.CardLayout).show(content, name)
        cardRefreshers[name]?.invoke()
        navigationButtons.forEach { (card, button) ->
            val selected = card == name || (name == SEARCH_CARD && card == DISCOVER_CARD)
            button.background = if (selected) MIXN_PRIMARY_CONTAINER else MIXN_NAV
            button.foreground = if (selected) MIXN_PRIMARY_DARK else MIXN_TEXT
        }
    }

    private fun NavigationPanel() = JPanel(GridBagLayout()).apply {
        preferredSize = Dimension(210, 0)
        background = MIXN_NAV
        border = BorderFactory.createEmptyBorder(18, 12, 18, 12)
        val constraints = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(4, 0, 4, 0)
        }
        constraints.gridy = 0
        add(JLabel("Mixn", SwingConstants.CENTER).apply {
            font = font.deriveFont(Font.BOLD, 30f)
            foreground = MIXN_PRIMARY
            border = BorderFactory.createEmptyBorder(8, 0, 22, 0)
        }, constraints)
        listOf(
            Triple("发现", DISCOVER_CARD, "discover"),
            Triple("书架", BOOKSHELF_CARD, "shelf"),
            Triple("我的", ACCOUNT_CARD, "account"),
        ).forEachIndexed { index, (label, card, icon) ->
            constraints.gridy = index + 1
            add(navButton(label, icon) { showCard(card) }.also { navigationButtons[card] = it }, constraints)
        }
        constraints.gridy = 4
        constraints.weighty = 1.0
        add(JPanel(), constraints)
        constraints.gridy = 5
        constraints.weighty = 0.0
        add(JLabel("双源阅读客户端", SwingConstants.CENTER).apply { foreground = MIXN_MUTED }, constraints)
    }

    private fun navButton(label: String, icon: String, action: () -> Unit) = JButton(label, NavIcon(icon)).apply {
        horizontalAlignment = SwingConstants.LEFT
        iconTextGap = 12
        isFocusPainted = false
        foreground = MIXN_TEXT
        background = MIXN_NAV
        border = BorderFactory.createEmptyBorder(11, 14, 11, 14)
        font = font.deriveFont(Font.PLAIN, 15f)
        addActionListener { action() }
    }

    private fun openBook(book: DesktopBook) {
        if (book.remoteId.isBlank()) {
            javax.swing.JOptionPane.showMessageDialog(
                this,
                "这本书缺少来源编号，暂时无法打开。请刷新列表后重试。",
                "无法打开作品",
                javax.swing.JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        BookDialog(this, state, book).isVisible = true
    }
}

private val MIXN_BG = Color(0xF7FAF9)
private val MIXN_NAV = Color(0xEEF5F3)
private val MIXN_CARD = Color(0xFFFFFF)
private val MIXN_PRIMARY = Color(0x0F766E)
private val MIXN_PRIMARY_DARK = Color(0x064E49)
private val MIXN_PRIMARY_CONTAINER = Color(0xCCFBF1)
private val MIXN_TEXT = Color(0x17201F)
private val MIXN_MUTED = Color(0x52605E)
private val MIXN_BORDER = Color(0xD6E3E0)

private class NavIcon(private val kind: String) : Icon {
    override fun getIconWidth(): Int = 20
    override fun getIconHeight(): Int = 20

    override fun paintIcon(component: java.awt.Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        g.translate(x, y)
        g.color = if (component.isEnabled) MIXN_PRIMARY else MIXN_MUTED
        g.stroke = java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        when (kind) {
            "discover" -> { g.drawRoundRect(3, 3, 14, 14, 3, 3); g.drawLine(6, 8, 14, 8); g.drawLine(6, 12, 12, 12) }
            "search" -> { g.drawOval(3, 3, 10, 10); g.drawLine(12, 12, 17, 17) }
            "shelf" -> { g.drawRoundRect(3, 3, 14, 14, 2, 2); g.drawLine(7, 3, 7, 17); g.drawLine(11, 3, 11, 17) }
            "history" -> { g.drawOval(3, 3, 14, 14); g.drawLine(10, 10, 10, 6); g.drawLine(10, 10, 14, 12) }
            "account" -> { g.drawOval(7, 3, 6, 6); g.drawArc(4, 10, 12, 9, 0, 180) }
            "back" -> { g.drawLine(16, 10, 4, 10); g.drawLine(4, 10, 10, 4); g.drawLine(4, 10, 10, 16) }
            "refresh" -> { g.drawArc(3, 3, 14, 14, 35, 285); g.drawLine(16, 5, 16, 10); g.drawLine(16, 5, 11, 5) }
            else -> { g.drawOval(4, 4, 12, 12); g.drawLine(10, 2, 10, 5); g.drawLine(10, 15, 10, 18); g.drawLine(2, 10, 5, 10); g.drawLine(15, 10, 18, 10) }
        }
        g.dispose()
    }
}

private object DesktopCoverImages {
    private val memory = ConcurrentHashMap<String, BufferedImage>()
    private val resized = ConcurrentHashMap<String, BufferedImage>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val pendingTargets = ConcurrentHashMap<String, MutableSet<JLabel>>()
    private val executor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "mixn-cover-loader").apply { isDaemon = true }
    }
    private val client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(8))
        .build()
    private val cacheDirectory: Path = Paths.get(System.getProperty("user.home"), ".mixn", "cache", "covers")

    fun load(url: String?, target: JLabel, width: Int, height: Int) {
        target.putClientProperty("mixn.cover.url", url)
        target.icon = null
        target.text = "加载封面…"
        target.background = Color(0xE9EEF5)
        if (url.isNullOrBlank()) {
            target.text = "暂无封面"
            return
        }
        val imageKey = "$url|$width|$height"
        resized[imageKey]?.let { apply(target, it); return }
        val lastFailure = failedAt[url]
        if (lastFailure != null && System.currentTimeMillis() - lastFailure < 30_000) {
            target.text = "暂无封面"
            return
        }
        val targets = pendingTargets.computeIfAbsent(imageKey) {
            Collections.newSetFromMap(ConcurrentHashMap<JLabel, Boolean>())
        }
        targets.add(target)
        if (targets.size > 1) return
        executor.execute {
            val image = memory[url] ?: runCatching { read(url) }.getOrNull()
            if (image == null) failedAt[url] = System.currentTimeMillis() else {
                failedAt.remove(url)
                memory[url] = image
            }
            val prepared = image?.let { crop(it, width, height) }
            if (prepared != null) resized[imageKey] = prepared
            val waiting = pendingTargets.remove(imageKey).orEmpty().toList()
            SwingUtilities.invokeLater {
                waiting.forEach { waitingTarget ->
                    if (waitingTarget.getClientProperty("mixn.cover.url") != url) return@forEach
                    if (prepared == null) waitingTarget.text = "暂无封面" else apply(waitingTarget, prepared)
                }
            }
        }
    }

    private fun read(url: String): BufferedImage? {
        Files.createDirectories(cacheDirectory)
        val cache = cacheDirectory.resolve(sha256(url) + ".img")
        if (Files.isRegularFile(cache)) {
            runCatching { ImageIO.read(cache.toFile()) }.getOrNull()?.let { return it }
            runCatching { Files.deleteIfExists(cache) }
        }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(12))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Mixn/1.4")
            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
            .header("Referer", "https://www.lightnovel.fun/")
            .GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) return null
        val bytes = response.body()
        val image = runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() ?: return null
        runCatching { Files.write(cache, bytes) }
        return image
    }

    private fun apply(target: JLabel, image: BufferedImage) {
        target.icon = ImageIcon(image)
        target.text = ""
    }

    private fun crop(image: BufferedImage, width: Int, height: Int): BufferedImage {
        val scale = maxOf(width.toDouble() / image.width, height.toDouble() / image.height)
        val scaledWidth = (image.width * scale).toInt().coerceAtLeast(width)
        val scaledHeight = (image.height * scale).toInt().coerceAtLeast(height)
        val scaled = image.getScaledInstance(scaledWidth, scaledHeight, java.awt.Image.SCALE_SMOOTH)
        val result = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = result.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(scaled, (width - scaledWidth) / 2, (height - scaledHeight) / 2, null)
        g.dispose()
        return result
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun shutdown() {
        executor.shutdownNow()
        pendingTargets.clear()
    }
}

private class BookGridPanel(
    private val onBook: (DesktopBook) -> Unit,
) : JPanel(BorderLayout()) {
    private val content = object : JPanel(GridLayout(0, 1, 16, 16)), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = Dimension(900, 600)
        override fun getScrollableUnitIncrement(visibleRect: java.awt.Rectangle?, orientation: Int, direction: Int): Int = 28
        override fun getScrollableBlockIncrement(visibleRect: java.awt.Rectangle?, orientation: Int, direction: Int): Int = 420
        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
    private val scroll = JScrollPane(content)
    private val items = mutableListOf<DesktopBook>()
    private var nearBottom: (() -> Unit)? = null
    private val nearBottomGate = DesktopNearBottomGate(260)

    init {
        background = MIXN_BG
        content.background = MIXN_BG
        content.border = BorderFactory.createEmptyBorder(4, 4, 24, 4)
        content.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent?) {
                val columns = ((width + 16) / 460).coerceIn(1, 3)
                val layout = content.layout as GridLayout
                if (layout.columns != columns) {
                    layout.columns = columns
                    render()
                }
            }
        })
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.background = MIXN_BG
        scroll.viewport.background = MIXN_BG
        scroll.verticalScrollBar.unitIncrement = 24
        scroll.verticalScrollBar.addAdjustmentListener {
            val bar = it.adjustable
            val distance = bar.maximum - (bar.value + bar.visibleAmount)
            if (nearBottomGate.update(distance)) nearBottom?.invoke()
        }
        add(scroll, BorderLayout.CENTER)
    }

    fun onNearBottom(action: () -> Unit) { nearBottom = action }

    fun rearmNearBottom() {
        nearBottomGate.arm()
        scheduleNearBottomCheck()
    }

    fun setItems(values: List<DesktopBook>) {
        items.clear(); items.addAll(values); nearBottomGate.arm(); render()
    }

    fun appendItems(values: List<DesktopBook>) {
        if (values.isEmpty()) return
        items.addAll(values)
        nearBottomGate.arm()
        values.forEach { content.add(BookCard(it, onBook)) }
        content.revalidate()
        content.repaint()
        scheduleNearBottomCheck()
    }

    private fun render() {
        content.removeAll()
        items.forEach { content.add(BookCard(it, onBook)) }
        content.revalidate(); content.repaint()
        scheduleNearBottomCheck()
    }

    private fun scheduleNearBottomCheck() {
        SwingUtilities.invokeLater {
            if (!isDisplayable) return@invokeLater
            val bar = scroll.verticalScrollBar
            val distance = bar.maximum - (bar.value + bar.visibleAmount)
            if (nearBottomGate.update(distance)) nearBottom?.invoke()
        }
    }
}

private open class RoundedSurfacePanel(
    private val surfaceColor: Color = MIXN_CARD,
    private val radius: Int = 8,
) : JPanel() {
    init { isOpaque = false }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = surfaceColor
        g.fillRoundRect(0, 0, width - 1, height - 1, radius, radius)
        g.color = MIXN_BORDER
        g.drawRoundRect(0, 0, width - 1, height - 1, radius, radius)
        g.dispose()
        super.paintComponent(graphics)
    }
}

private class BookCard(
    private val book: DesktopBook,
    onBook: (DesktopBook) -> Unit,
) : RoundedSurfacePanel(MIXN_CARD, 8) {
    private val cover = JLabel("封面", SwingConstants.CENTER)

    init {
        layout = BorderLayout(14, 0)
        preferredSize = Dimension(430, 176)
        minimumSize = Dimension(280, 176)
        border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        cover.preferredSize = Dimension(76, 108)
        cover.minimumSize = cover.preferredSize
        cover.maximumSize = cover.preferredSize
        cover.isOpaque = true
        cover.font = cover.font.deriveFont(Font.PLAIN, 13f)
        cover.foreground = MIXN_MUTED
        add(cover, BorderLayout.WEST)
        val sourceName = if (book.sourceId == DESKTOP_KINGDOM_SOURCE_ID) "轻之国度" else "轻书架"
        val title = JLabel(clipCardText(book.title, 52)).apply {
            font = font.deriveFont(Font.BOLD, 15f)
            foreground = MIXN_TEXT
            toolTipText = book.title
            alignmentX = LEFT_ALIGNMENT
            preferredSize = Dimension(0, 28)
            minimumSize = Dimension(0, 28)
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        }
        val meta = JLabel("${book.author.ifBlank { "作者未知" }} · $sourceName")
        meta.font = meta.font.deriveFont(Font.PLAIN, 12f)
        meta.foreground = MIXN_PRIMARY
        meta.alignmentX = LEFT_ALIGNMENT
        meta.preferredSize = Dimension(0, 22)
        meta.minimumSize = Dimension(0, 22)
        meta.maximumSize = Dimension(Int.MAX_VALUE, 22)
        val summary = JTextArea(book.summary.ifBlank { "暂无简介" }).apply {
            isEditable = false
            isOpaque = false
            lineWrap = true
            wrapStyleWord = true
            rows = 2
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = MIXN_TEXT
            border = BorderFactory.createEmptyBorder(3, 0, 0, 0)
            alignmentX = LEFT_ALIGNMENT
            text = clipCardText(text, 118)
            preferredSize = Dimension(0, 46)
            minimumSize = Dimension(0, 46)
            maximumSize = Dimension(Int.MAX_VALUE, 46)
        }
        val details = JPanel().apply {
            isOpaque = false
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(title)
            add(meta)
            add(summary)
        }
        add(details, BorderLayout.CENTER)
        addMouseListener(openListener(onBook))
        listOf(cover, title, meta, details).forEach { it.addMouseListener(openListener(onBook)) }
        DesktopCoverImages.load(book.coverUrl, cover, 76, 108)
    }

    private fun openListener(onBook: (DesktopBook) -> Unit) = object : MouseAdapter() {
        override fun mouseClicked(event: MouseEvent) {
            if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) onBook(book)
        }
    }
}

private fun clipCardText(value: String, maxLength: Int): String = value.trim().let {
    if (it.length <= maxLength) it else it.take((maxLength - 1).coerceAtLeast(1)).trimEnd() + "…"
}

private class MixnTabBar<T>(
    initialItems: List<T>,
    private val label: (T) -> String,
    private val onSelected: (T) -> Unit,
) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {
    private val values = mutableListOf<T>()
    private var selectedIndex = 0

    init {
        background = MIXN_BG
        setItems(initialItems)
    }

    fun setItems(items: List<T>) {
        values.clear()
        values.addAll(items)
        selectedIndex = selectedIndex.coerceIn(0, (values.size - 1).coerceAtLeast(0))
        removeAll()
        values.forEachIndexed { index, value ->
            add(JButton(label(value)).apply {
                isFocusPainted = false
                font = font.deriveFont(Font.PLAIN, 13f)
                border = BorderFactory.createEmptyBorder(7, 12, 7, 12)
                addActionListener {
                    selectedIndex = index
                    refreshState()
                    onSelected(value)
                }
            })
        }
        refreshState()
        revalidate()
        repaint()
    }

    private fun refreshState() {
        components.forEachIndexed { index, component ->
            val button = component as JButton
            val selected = index == selectedIndex
            button.background = if (selected) MIXN_PRIMARY_CONTAINER else MIXN_BG
            button.foreground = if (selected) MIXN_PRIMARY_DARK else MIXN_MUTED
        }
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        components.forEach { it.isEnabled = enabled }
    }
}

private class DiscoverPanel(
    private val state: DesktopRuntimeState,
    private val onBook: (DesktopBook) -> Unit,
    private val onSearch: () -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private var selectedSource = state.registry.all().firstOrNull()
    private var selectedFeed: DesktopFeed? = null
    private val sourceTabs = MixnTabBar(state.registry.all(), DesktopSource::displayName) { source ->
        selectedSource = source
        selectedFeed = source.feeds.firstOrNull()
        feedTabs.setItems(source.feeds)
        if (!requestInFlight) refresh()
    }
    private val feedTabs = MixnTabBar<DesktopFeed>(emptyList(), DesktopFeed::label) { feed ->
        selectedFeed = feed
        if (!requestInFlight) refresh()
    }
    private val status = JLabel("选择来源后加载")
    private val grid = BookGridPanel(onBook)
    private val paging = DesktopPagingController(PAGE_SIZE)
    private val pageLoader = DesktopSourcePageLoader(state.registry)
    private var requestInFlight = false

    init {
        background = MIXN_BG
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        add(JPanel(BorderLayout(0, 8)).apply {
            background = MIXN_BG
            add(header("发现", "在两个来源中浏览榜单和新书", onSearch), BorderLayout.NORTH)
            add(toolbar(), BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        grid.onNearBottom(::loadMore)
        add(grid, BorderLayout.CENTER)
        updateFeeds()
        refresh()
    }

    private fun toolbar() = JPanel(BorderLayout(8, 0)).apply {
        background = MIXN_BG
        add(JPanel(BorderLayout(0, 2)).apply {
            background = MIXN_BG
            add(sourceTabs, BorderLayout.NORTH)
            add(feedTabs, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 0)).apply {
            background = MIXN_BG
            add(JButton(NavIcon("refresh")).apply {
                toolTipText = "刷新"
                preferredSize = Dimension(34, 30)
                isFocusPainted = false
                addActionListener { refresh() }
            }, BorderLayout.WEST)
            add(status, BorderLayout.CENTER)
        }, BorderLayout.EAST)
    }

    private fun updateFeeds() {
        val selected = selectedSource?.feeds.orEmpty()
        selectedFeed = selected.firstOrNull()
        feedTabs.setItems(selected)
    }

    private fun refresh() {
        val feed = selectedFeed ?: return
        val source = selectedSource ?: return
        runPaged("加载发现", true) { paging.loadInitial(pageLoader, source.id, feed.label, "") }
    }

    private fun loadMore() {
        if (requestInFlight || !paging.hasMore || paging.loading) return
        val feed = selectedFeed ?: return
        val source = selectedSource ?: return
        runPaged("加载更多", false) { paging.loadMore(pageLoader, source.id, feed.label, "") }
    }

    private fun runPaged(label: String, initial: Boolean, action: () -> Boolean) {
        if (requestInFlight) return
        requestInFlight = true
        val previousCount = paging.items.size
        status.text = "$label…"
        sourceTabs.isEnabled = false
        feedTabs.isEnabled = false
        object : SwingWorker<Boolean, Unit>() {
            private var failure: Throwable? = null
            override fun doInBackground(): Boolean = runCatching { action() }
                .onFailure { failure = it }
                .getOrDefault(false)

            override fun done() {
                requestInFlight = false
                sourceTabs.isEnabled = true
                feedTabs.isEnabled = true
                if (failure != null) {
                    if (initial) { paging.reset(); grid.setItems(emptyList()) }
                    else grid.rearmNearBottom()
                    status.text = failure?.message ?: "加载失败"
                    return
                }
                if (initial) grid.setItems(paging.items)
                else grid.appendItems(paging.items.drop(previousCount))
                status.text = "${paging.items.size} 本${if (paging.hasMore) "，下滑加载更多" else ""}"
            }
        }.execute()
    }
}

private class SearchPanel(
    private val state: DesktopRuntimeState,
    private val onBook: (DesktopBook) -> Unit,
    private val onBack: () -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private val query = JTextField()
    private val status = JLabel("输入关键词后搜索两站")
    private val grid = BookGridPanel(onBook)
    private val controllers = state.registry.all().associate { it.id to DesktopPagingController(PAGE_SIZE) }
    private val errors = mutableMapOf<String, String>()
    private var requestInFlight = false
    private var activeQuery = ""

    init {
        background = MIXN_BG
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        grid.onNearBottom(::loadMore)
        add(grid, BorderLayout.CENTER)
        val controls = JPanel(BorderLayout(8, 0)).apply { background = MIXN_BG }
        controls.add(query, BorderLayout.CENTER)
        controls.add(JButton(NavIcon("search")).apply {
            toolTipText = "搜索"
            preferredSize = Dimension(38, 34)
            isFocusPainted = false
            addActionListener { refresh() }
        }, BorderLayout.EAST)
        query.addActionListener { refresh() }
        add(JPanel(BorderLayout(0, 6)).apply {
            background = MIXN_BG
            add(header("聚合搜索", "同时搜索轻之国度和轻书架", onBack, actionIcon = "back"), BorderLayout.NORTH)
            add(controls, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(status, BorderLayout.SOUTH)
    }

    private fun refresh() {
        val value = query.text.trim()
        if (value.isEmpty()) { status.text = "请输入书名、作者或关键词"; return }
        if (requestInFlight) return
        requestInFlight = true
        activeQuery = value
        grid.setItems(emptyList())
        errors.clear()
        controllers.values.forEach(DesktopPagingController::reset)
        status.text = "搜索中…"
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                val sources = this@SearchPanel.state.registry.all()
                val loader = DesktopSourcePageLoader(this@SearchPanel.state.registry)
                val jobs = sources.map { source ->
                    this@SearchPanel.state.sourceExecutor.submit {
                        runCatching {
                            controllers.getValue(source.id).loadInitial(loader, source.id, "", value)
                        }.onFailure { error -> synchronized(errors) { errors[source.id] = error.message ?: "加载失败" } }
                    }
                }
                jobs.forEach { it.get() }
            }
            override fun done() { requestInFlight = false; renderAll(); updateStatus() }
        }.execute()
    }

    private fun loadMore() {
        if (requestInFlight || activeQuery.isBlank()) return
        val targets = state.registry.all().filter { controllers.getValue(it.id).hasMore }
        if (targets.isEmpty()) return
        requestInFlight = true
        status.text = "加载更多…"
        val previous = controllers.mapValues { it.value.items.size }
        object : SwingWorker<Unit, Unit>() {
            override fun doInBackground() {
                val loader = DesktopSourcePageLoader(this@SearchPanel.state.registry)
                val jobs = targets.map { source ->
                    this@SearchPanel.state.sourceExecutor.submit {
                        runCatching {
                            controllers.getValue(source.id).loadMore(loader, source.id, "", activeQuery)
                        }.onFailure { error -> synchronized(errors) { errors[source.id] = error.message ?: "加载失败" } }
                    }
                }
                jobs.forEach { it.get() }
            }
            override fun done() {
                requestInFlight = false
                this@SearchPanel.state.registry.all().flatMap { source -> controllers.getValue(source.id).items.drop(previous.getValue(source.id)) }
                    .let(grid::appendItems)
                if (errors.isNotEmpty()) grid.rearmNearBottom()
                updateStatus()
            }
        }.execute()
    }

    private fun renderAll() {
        grid.setItems(state.registry.all().flatMap { source -> controllers.getValue(source.id).items })
    }

    private fun updateStatus() {
        val loaded = controllers.values.sumOf { it.items.size }
        val more = controllers.values.any { it.hasMore }
        val failed = errors.values.distinct()
        status.text = buildString {
            append("$loaded 本")
            if (more) append("，下滑加载更多")
            if (failed.isNotEmpty()) append("；部分来源失败：").append(failed.joinToString(" / "))
        }
    }
}

private class BookshelfPanel(
    private val state: DesktopRuntimeState,
    private val onBook: (DesktopBook) -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private val grid = BookGridPanel(onBook)
    private val status = JLabel("登录来源后同步书架")
    private var loaded = false
    private var requestInFlight = false

    init {
        background = MIXN_BG
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        add(JPanel(BorderLayout(0, 8)).apply {
            background = MIXN_BG
            add(header("统一书架", "两站收藏合并展示", null), BorderLayout.NORTH)
            add(JPanel(BorderLayout(8, 0)).apply {
                background = MIXN_BG
                add(JButton(NavIcon("refresh")).apply {
                    toolTipText = "刷新书架"
                    preferredSize = Dimension(34, 30)
                    isFocusPainted = false
                    addActionListener { refresh() }
                }, BorderLayout.WEST)
                add(status, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(grid, BorderLayout.CENTER)
    }

    fun refreshIfNeeded() {
        if (!loaded) {
            loaded = true
            refresh()
        }
    }

    private fun refresh() {
        if (requestInFlight) return
        requestInFlight = true
        status.text = "同步书架中…"
        object : SwingWorker<List<DesktopBook>, Unit>() {
            private val failures = mutableListOf<String>()
            override fun doInBackground(): List<DesktopBook> = this@BookshelfPanel.state.registry.all().flatMap { source ->
                runCatching { source.bookshelf() }.getOrElse { failures += "${source.displayName}：${it.message}"; emptyList() }
            }.distinctBy(DesktopBook::key)

            override fun done() {
                requestInFlight = false
                runCatching { get() }.onSuccess { booksList ->
                    grid.setItems(booksList)
                    status.text = "${booksList.size} 本${if (failures.isEmpty()) "" else "；${failures.joinToString(" / ")}"}"
                }.onFailure { status.text = it.message ?: "书架加载失败" }
            }
        }.execute()
    }
}

private class AccountPanel(
    private val state: DesktopRuntimeState,
    private val onHistory: () -> Unit,
    private val onSettings: () -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private val cards = JPanel(GridBagLayout())

    init {
        background = MIXN_BG
        cards.background = MIXN_BG
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        val constraints = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            weightx = 1.0
            insets = Insets(8, 0, 8, 0)
        }
        state.registry.all().forEachIndexed { index, source ->
            constraints.gridy = index
            cards.add(SourceAccountCard(source), constraints)
        }
        add(JPanel(BorderLayout(0, 12)).apply {
            background = MIXN_BG
            add(header("我的", "账号、阅读记录与应用设置"), BorderLayout.NORTH)
            add(cards, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 8)).apply {
            background = MIXN_BG
            add(JLabel("两个来源的登录态、资料和签到彼此独立。"), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                background = MIXN_BG
                add(JButton("阅读记录").apply { addActionListener { onHistory() } })
                add(JButton("设置").apply { addActionListener { onSettings() } })
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    private inner class SourceAccountCard(private val source: DesktopSource) : RoundedSurfacePanel(MIXN_CARD, 8) {
        private val identifier = JTextField(18)
        private val password = JPasswordField(18)
        private val status = JLabel("正在恢复会话…")

        init {
            layout = BorderLayout(8, 8)
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            val title = JLabel(source.displayName).apply {
                font = font.deriveFont(Font.BOLD, 18f)
                foreground = MIXN_TEXT
            }
            identifier.preferredSize = Dimension(180, 32)
            password.preferredSize = Dimension(180, 32)
            val form = JPanel(GridBagLayout())
            form.isOpaque = false
            val fieldConstraints = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(2, 0, 2, 8)
                anchor = GridBagConstraints.CENTER
            }
            fun field(label: String, component: java.awt.Component, column: Int) {
                fieldConstraints.gridy = 0
                fieldConstraints.gridx = column
                fieldConstraints.weightx = 0.0
                form.add(JLabel(label).apply { foreground = MIXN_MUTED }, fieldConstraints)
                fieldConstraints.gridx = column + 1
                fieldConstraints.weightx = 1.0
                form.add(component, fieldConstraints)
            }
            field(if (source.id == DESKTOP_SHELF_SOURCE_ID) "邮箱" else "用户名/邮箱", identifier, 0)
            field("密码", password, 2)
            fieldConstraints.gridy = 1
            fieldConstraints.gridx = 0
            fieldConstraints.gridwidth = 4
            fieldConstraints.weightx = 1.0
            fieldConstraints.insets = Insets(6, 0, 0, 8)
            form.add(JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(JButton("登录").apply { addActionListener { login() } })
                add(JButton("退出").apply { addActionListener { logout() } })
                add(JButton("资料").apply { addActionListener { profile() } })
                if (source.id == DESKTOP_SHELF_SOURCE_ID) add(JButton("签到").apply { addActionListener { reward() } })
            }, fieldConstraints)
            add(title, BorderLayout.NORTH)
            add(form, BorderLayout.CENTER)
            add(status.apply {
                foreground = MIXN_MUTED
                border = BorderFactory.createEmptyBorder(3, 0, 0, 0)
            }, BorderLayout.SOUTH)
            restore()
        }

        private fun restore() = runAsync("恢复会话") { source.restoreSession() }.onSuccess { session ->
            status.text = if (session.loggedIn) "已登录：${session.displayName ?: session.accountId ?: "用户"}" else "未登录"
        }

        private fun login() = runAsync("登录") { source.login(identifier.text, String(password.password)) }.onSuccess { session ->
            password.text = ""
            status.text = "已登录：${session.displayName ?: session.accountId ?: "用户"}"
        }

        private fun logout() = runAsync("退出") { source.logout(); DesktopSession(source.id, false) }.onSuccess {
            status.text = "已退出"
        }

        private fun profile() = runAsync("读取资料") { source.profile() }.onSuccess { profile ->
            status.text = profileText(profile)
        }

        private fun reward() = runAsync("签到") { source.claimDailyReward() }.onSuccess { reward ->
            status.text = if (reward.claimedToday) "签到完成，奖励 ${reward.amount}，余额 ${reward.balance ?: "未知"}" else "今日未签到"
        }
    }
}

private class HistoryPanel(
    private val state: DesktopRuntimeState,
    private val onBook: (DesktopBook) -> Unit,
) : JPanel(BorderLayout(0, 12)) {
    private val grid = BookGridPanel(onBook)
    private val status = JLabel("登录来源后读取阅读历史")
    private var loaded = false
    private var requestInFlight = false

    init {
        background = MIXN_BG
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        add(JPanel(BorderLayout(0, 8)).apply {
            background = MIXN_BG
            add(header("阅读历史", "最近阅读的双源作品", null), BorderLayout.NORTH)
            add(JPanel(BorderLayout(8, 0)).apply {
                background = MIXN_BG
                add(JButton(NavIcon("refresh")).apply {
                    toolTipText = "刷新历史"
                    preferredSize = Dimension(34, 30)
                    isFocusPainted = false
                    addActionListener { refresh() }
                }, BorderLayout.WEST)
                add(status, BorderLayout.CENTER)
            }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(grid, BorderLayout.CENTER)
    }

    fun refreshIfNeeded() {
        if (!loaded) {
            loaded = true
            refresh()
        }
    }

    private fun refresh() {
        if (requestInFlight) return
        requestInFlight = true
        status.text = "读取历史中…"
        object : SwingWorker<List<DesktopBook>, Unit>() {
            private val failures = mutableListOf<String>()
            override fun doInBackground(): List<DesktopBook> = this@HistoryPanel.state.registry.all().flatMap { source ->
                runCatching { source.history() }.getOrElse { failures += "${source.displayName}：${it.message}"; emptyList() }
            }.distinctBy(DesktopBook::key)

            override fun done() {
                requestInFlight = false
                runCatching { get() }.onSuccess { history ->
                    grid.setItems(history)
                    status.text = "${history.size} 本${if (failures.isEmpty()) "" else "；${failures.joinToString(" / ")}"}"
                }.onFailure { status.text = it.message ?: "历史加载失败" }
            }
        }.execute()
    }
}

private data class DesktopChoice(val label: String, val value: String) {
    override fun toString(): String = label
}

private class SettingsPanel(
    private val state: DesktopRuntimeState,
) : JPanel(BorderLayout(0, 12)) {
    init {
        background = MIXN_BG
        border = BorderFactory.createEmptyBorder(18, 24, 18, 24)
        val form = JPanel(GridBagLayout())
        form.isOpaque = false
        val constraints = GridBagConstraints().apply { fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(8, 0, 8, 12) }
        val fonts = JComboBox(arrayOf("Serif", "SansSerif", "Monospaced"))
        fonts.selectedItem = state.preferences.fontName
        val size = JSpinner(SpinnerNumberModel(state.preferences.fontSize, 14, 32, 1))
        val modes = JComboBox(arrayOf(DesktopChoice("左右翻页", "page"), DesktopChoice("上下滚动", "scroll")))
        modes.selectedItem = (0 until modes.itemCount).map { modes.getItemAt(it) }.firstOrNull { it.value == state.preferences.readerMode }
        val lineHeight = JSpinner(SpinnerNumberModel(state.preferences.lineHeight, 1.2, 2.2, 0.05))
        val horizontalPadding = JSpinner(SpinnerNumberModel(state.preferences.horizontalPadding, 16, 96, 2))
        val themes = JComboBox(arrayOf(
            DesktopChoice("白色", "white"),
            DesktopChoice("米黄", "sepia"),
            DesktopChoice("护眼绿", "green"),
            DesktopChoice("深色", "dark"),
        ))
        themes.selectedItem = (0 until themes.itemCount).map { themes.getItemAt(it) }.firstOrNull { it.value == state.preferences.readerTheme }
        val showProgress = JCheckBox("显示阅读进度条", state.preferences.showProgressBar).apply { isOpaque = false }
        val directory = JTextField(state.preferences.downloadDirectory, 30)
        directory.preferredSize = Dimension(360, 34)
        fun addRow(row: Int, label: String, component: java.awt.Component) {
            constraints.gridy = row; constraints.gridx = 0; constraints.weightx = 0.0; form.add(JLabel(label), constraints)
            constraints.gridx = 1; constraints.weightx = 1.0; form.add(component, constraints)
        }
        addRow(0, "阅读字体", fonts)
        addRow(1, "字号", size)
        addRow(2, "阅读模式", modes)
        addRow(3, "行高", lineHeight)
        addRow(4, "正文边距", horizontalPadding)
        addRow(5, "阅读背景", themes)
        addRow(6, "阅读进度", showProgress)
        addRow(7, "下载目录", JPanel(BorderLayout(8, 0)).apply {
            add(directory, BorderLayout.CENTER)
            add(JButton("选择").apply { addActionListener { chooseDirectory(directory) } }, BorderLayout.EAST)
        })
        val save = JButton("保存设置")
        val saveStatus = JLabel("")
        saveStatus.foreground = MIXN_MUTED
        save.addActionListener {
            state.preferences.fontName = fonts.selectedItem?.toString() ?: "Serif"
            state.preferences.fontSize = (size.value as Number).toInt()
            state.preferences.readerMode = (modes.selectedItem as? DesktopChoice)?.value ?: "scroll"
            state.preferences.lineHeight = (lineHeight.value as Number).toDouble()
            state.preferences.horizontalPadding = (horizontalPadding.value as Number).toInt()
            state.preferences.readerTheme = (themes.selectedItem as? DesktopChoice)?.value ?: "sepia"
            state.preferences.showProgressBar = showProgress.isSelected
            state.preferences.downloadDirectory = directory.text.trim()
            state.preferences.flush()
            saveStatus.text = "已保存"
        }
        constraints.gridy = 8; constraints.gridx = 1; constraints.weightx = 1.0; form.add(save, constraints)
        constraints.gridy = 9; constraints.gridx = 1; constraints.weightx = 1.0; form.add(saveStatus, constraints)
        val settingsCard = RoundedSurfacePanel(MIXN_CARD, 8).apply {
            layout = BorderLayout()
            border = BorderFactory.createEmptyBorder(12, 16, 12, 16)
            add(form, BorderLayout.CENTER)
        }
        add(JPanel(BorderLayout(0, 12)).apply {
            background = MIXN_BG
            add(header("设置"), BorderLayout.NORTH)
            add(settingsCard, BorderLayout.CENTER)
        }, BorderLayout.NORTH)
    }

    private fun chooseDirectory(target: JTextField) {
        JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }.also { chooser ->
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) target.text = chooser.selectedFile.absolutePath
        }
    }
}

private class BookDialog(
    owner: JFrame,
    private val state: DesktopRuntimeState,
    private val book: DesktopBook,
) : JDialog(owner, book.title, true) {
    private val chaptersModel = DefaultListModel<DesktopChapter>()
    private val chaptersList = JList(chaptersModel)
    private val status = JLabel("正在读取详情…")
    private val shelfButton = JButton("加入书架")
    private val cover = JLabel("封面", SwingConstants.CENTER)
    private val titleLabel = JLabel(book.title)
    private val authorLabel = JLabel(book.author.ifBlank { "作者未知" })
    private val description = JTextArea(book.summary)

    init {
        size = Dimension(900, 680)
        setLocationRelativeTo(owner)
        background = MIXN_BG
        contentPane.background = MIXN_BG
        layout = BorderLayout(14, 14)
        rootPane.border = BorderFactory.createEmptyBorder(18, 18, 18, 18)
        cover.preferredSize = Dimension(150, 210)
        cover.minimumSize = cover.preferredSize
        cover.maximumSize = cover.preferredSize
        cover.isOpaque = true
        cover.background = Color(0xE9EEF5)
        cover.foreground = MIXN_MUTED
        DesktopCoverImages.load(book.coverUrl, cover, 150, 210)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 25f)
        titleLabel.foreground = MIXN_TEXT
        authorLabel.foreground = MIXN_MUTED
        description.isEditable = false
        description.lineWrap = true
        description.wrapStyleWord = true
        description.rows = 5
        description.background = MIXN_BG
        description.foreground = MIXN_TEXT
        description.border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
        val intro = JPanel(BorderLayout(0, 8)).apply {
            background = MIXN_BG
            add(titleLabel, BorderLayout.NORTH)
            add(authorLabel, BorderLayout.CENTER)
            add(description, BorderLayout.SOUTH)
        }
        val hero = JPanel(BorderLayout(16, 0)).apply {
            background = MIXN_BG
            add(cover, BorderLayout.WEST)
            add(intro, BorderLayout.CENTER)
        }
        add(JPanel(BorderLayout(0, 8)).apply { background = MIXN_BG; add(hero, BorderLayout.CENTER); add(status, BorderLayout.SOUTH) }, BorderLayout.NORTH)
        chaptersList.cellRenderer = ChapterCellRenderer()
        chaptersList.background = MIXN_CARD
        chaptersList.foreground = MIXN_TEXT
        chaptersList.toolTipText = "单击章节开始阅读"
        chaptersList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && event.clickCount == 1) {
                    chaptersList.selectedValue?.let(::openChapter)
                }
            }
        })
        add(JScrollPane(chaptersList).apply { border = BorderFactory.createLineBorder(MIXN_BORDER) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(8, 0)).apply {
            add(shelfButton, BorderLayout.WEST)
            add(JButton("刷新目录").apply { addActionListener { loadDetails() } }, BorderLayout.CENTER)
            add(JButton("关闭").apply { addActionListener { dispose() } }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        shelfButton.addActionListener { toggleShelf() }
        loadDetails()
    }

    private fun loadDetails() {
        status.text = "正在读取详情和目录…"
        object : SwingWorker<DetailAndChapters, Unit>() {
            private var failure: Throwable? = null
            override fun doInBackground(): DetailAndChapters = runCatching {
                val source = this@BookDialog.state.registry.get(book.sourceId) ?: error("未知来源")
                val detail = source.detail(book.remoteId)
                val chapters = source.volumes(book.remoteId).flatMap { volume ->
                    loadAllDesktopChapterPages(
                        load = { page -> source.chapters(book.remoteId, volume.remoteId, page, 50) },
                    )
                }
                DetailAndChapters(detail, chapters)
            }.onFailure { failure = it }.getOrNull() ?: throw failure ?: IllegalStateException("详情加载失败")

            override fun done() {
                val result = runCatching { get() }.getOrElse {
                    status.text = "详情加载失败，请点击“刷新目录”重试"
                    status.toolTipText = it.message
                    return
                }
                chaptersModel.clear(); result.chapters.forEach(chaptersModel::addElement)
                titleLabel.text = result.detail.book.title
                authorLabel.text = "${result.detail.book.author.ifBlank { "作者未知" }} · ${if (result.detail.book.sourceId == DESKTOP_KINGDOM_SOURCE_ID) "轻之国度" else "轻书架"}"
                description.text = result.detail.description.ifBlank { "暂无简介" }
                DesktopCoverImages.load(result.detail.book.coverUrl, cover, 150, 210)
                shelfButton.text = if (book.inRemoteShelf == true || result.detail.book.inRemoteShelf == true) "移出书架" else "加入书架"
                val readableHint = when (result.detail.book.isReadable) {
                    false -> " · 暂不可读${result.detail.book.readabilityNote?.let { "：$it" }.orEmpty()}"
                    else -> ""
                }
                status.text = "${result.detail.book.title} · ${result.chapters.size} 章${if (result.chapters.any { it.locked }) " · 含锁定章节" else ""}$readableHint"
                status.toolTipText = status.text
            }
        }.execute()
    }

    private fun toggleShelf() {
        val source = state.registry.get(book.sourceId) ?: return
        val add = shelfButton.text == "加入书架"
        runAsync(if (add) "加入书架" else "移出书架") { source.setBookshelf(book.remoteId, add) }.onSuccess {
            shelfButton.text = if (it) "移出书架" else "加入书架"
            status.text = if (it) "已加入书架" else "已移出书架"
        }
    }

    private fun openChapter(chapter: DesktopChapter) {
        val source = state.registry.get(book.sourceId) ?: return
        if (chapter.locked) {
            if (source.id != DESKTOP_KINGDOM_SOURCE_ID) { status.text = "该章节需要在来源客户端解锁"; return }
            val price = chapter.coinPrice?.let { "，价格 $it 轻币" }.orEmpty()
            if (javax.swing.JOptionPane.showConfirmDialog(this, "该章节已锁定$price，是否调用轻之国度官方接口解锁？", "章节解锁", javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) return
            runAsync("解锁章节") { source.unlockChapter(book.remoteId, chapter.remoteId) }.onSuccess { loadDetails() }
            return
        }
        ReaderDialog(this, state, source, book, chapter, chaptersModel.elements().asSequence().toList()).isVisible = true
    }
}

private data class DetailAndChapters(val detail: DesktopBookDetail, val chapters: List<DesktopChapter>)

private data class DesktopReaderPalette(
    val background: Color,
    val text: Color,
    val mutedText: Color,
)

private fun DesktopReaderPalette.css(color: Color): String =
    "#%02x%02x%02x".format(color.red, color.green, color.blue)

private fun desktopReaderPalette(theme: String): DesktopReaderPalette = when (theme) {
    "white" -> DesktopReaderPalette(Color(247, 248, 250), Color(36, 39, 45), Color(101, 107, 117))
    "green" -> DesktopReaderPalette(Color(234, 242, 234), Color(38, 51, 42), Color(97, 112, 100))
    "dark" -> DesktopReaderPalette(Color(28, 29, 32), Color(237, 239, 242), Color(169, 175, 184))
    else -> DesktopReaderPalette(Color(243, 240, 234), Color(43, 47, 54), Color(112, 108, 101))
}

private class ReaderDialog(
    owner: Dialog,
    private val state: DesktopRuntimeState,
    private val source: DesktopSource,
    private val book: DesktopBook,
    initialChapter: DesktopChapter,
    private val chapters: List<DesktopChapter>,
) : JDialog(owner, initialChapter.title, true) {
    private val titleLabel = JLabel(initialChapter.title)
    private val progressLabel = JLabel("加载中…")
    private val progressBar = JProgressBar(0, 100)
    private val editor = javax.swing.JEditorPane()
    private val scroll = JScrollPane(editor)
    private val previous = JButton("‹ 上一章")
    private val next = JButton("下一章 ›")
    private val saveOffline = JButton("保存本章")
    private val export = JButton("导出 EPUB")
    private val chrome = JPanel(BorderLayout(10, 8))
    private val footer = JPanel(BorderLayout())
    // Match the Android reader: start with an unobstructed page and reveal
    // title/actions/progress with a tap in the middle of the text.
    private var chromeVisible = false
    private var current = initialChapter
    private val palette = desktopReaderPalette(state.preferences.readerTheme)

    init {
        size = Dimension(920, 720)
        setLocationRelativeTo(owner)
        contentPane.background = palette.background
        layout = BorderLayout()
        rootPane.border = BorderFactory.createEmptyBorder(12, 18, 12, 18)
        titleLabel.font = titleLabel.font.deriveFont(Font.BOLD, 17f)
        titleLabel.foreground = palette.text
        chrome.background = palette.background
        val titleBar = JPanel(BorderLayout()).apply {
            background = palette.background
            add(titleLabel, BorderLayout.WEST)
            add(JButton("×").apply {
                toolTipText = "关闭阅读器"
                isFocusPainted = false
                foreground = palette.text
                background = palette.background
                border = BorderFactory.createEmptyBorder(2, 8, 2, 8)
                addActionListener { dispose() }
            }, BorderLayout.EAST)
        }
        chrome.add(titleBar, BorderLayout.NORTH)
        editor.contentType = "text/html"
        editor.editorKit = HTMLEditorKit()
        editor.isEditable = false
        editor.background = palette.background
        editor.foreground = palette.text
        editor.font = Font(state.preferences.fontName, Font.PLAIN, state.preferences.fontSize)
        editor.border = BorderFactory.createEmptyBorder(10, 0, 28, 0)
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.background = palette.background
        scroll.viewport.background = palette.background
        add(chrome, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        val actionBar = JPanel(BorderLayout(8, 0)).apply {
            background = palette.background
            previous.foreground = palette.text
            next.foreground = palette.text
            add(previous, BorderLayout.WEST)
            add(next, BorderLayout.EAST)
            add(JPanel(FlowLayout(FlowLayout.CENTER, 8, 0)).apply { background = palette.background; add(progressLabel) }, BorderLayout.CENTER)
        }
        progressLabel.foreground = palette.mutedText
        val progressRow = JPanel(BorderLayout(8, 6)).apply {
            background = palette.background
            progressBar.isStringPainted = false
            progressBar.foreground = MIXN_PRIMARY
            progressBar.background = Color(palette.text.red, palette.text.green, palette.text.blue, 38)
            progressBar.isOpaque = false
            progressBar.isVisible = state.preferences.showProgressBar
            add(progressBar, BorderLayout.NORTH)
            add(actionBar, BorderLayout.CENTER)
        }
        val tools = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            background = palette.background
            add(saveOffline); add(export)
        }
        footer.background = palette.background
        footer.add(progressRow, BorderLayout.CENTER)
        footer.add(tools, BorderLayout.SOUTH)
        add(footer, BorderLayout.SOUTH)
        previous.addActionListener { adjacent(-1) }
        next.addActionListener { adjacent(1) }
        saveOffline.addActionListener { downloadCurrent() }
        export.addActionListener { exportEpub() }
        val toggleChrome = object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1) return
                val width = (event.component.width).coerceAtLeast(1)
                val fraction = event.x.toDouble() / width
                if (state.preferences.readerMode == "page" && fraction < 0.25) {
                    pageTurn(-1)
                } else if (state.preferences.readerMode == "page" && fraction > 0.75) {
                    pageTurn(1)
                } else {
                    chromeVisible = !chromeVisible
                    chrome.isVisible = chromeVisible
                    footer.isVisible = chromeVisible
                    revalidate(); repaint()
                }
            }
        }
        editor.addMouseListener(toggleChrome)
        scroll.viewport.addMouseListener(toggleChrome)
        scroll.verticalScrollBar.addAdjustmentListener { event ->
            if (!event.valueIsAdjusting) {
                val max = scroll.verticalScrollBar.maximum - scroll.verticalScrollBar.visibleAmount
                val percent = if (max <= 0) 100 else (event.value * 100 / max).coerceIn(0, 100)
                progressLabel.text = "${current.title} · $percent%"
                progressBar.value = percent
                state.progress.write(book, current.remoteId, percent)
            }
        }
        val scrubProgress = { x: Int ->
            if (progressBar.isVisible) {
                val width = progressBar.width.coerceAtLeast(1)
                val percent = (x.toDouble() / width * 100.0).roundToInt().coerceIn(0, 100)
                progressBar.value = percent
                val bar = scroll.verticalScrollBar
                val max = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)
                bar.value = (max * percent / 100).coerceIn(0, max)
            }
        }
        progressBar.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = scrubProgress(event.x)
        })
        progressBar.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(event: MouseEvent) = scrubProgress(event.x)
        })
        chrome.isVisible = false
        footer.isVisible = false
        configureReadingMode()
        load(initialChapter)
    }

    private fun pageTurn(direction: Int) {
        val bar = scroll.verticalScrollBar
        val step = bar.visibleAmount.coerceAtLeast(1)
        val max = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)
        val previousValue = bar.value
        bar.value = (bar.value + direction * step).coerceIn(0, max)
        if (previousValue == 0 && direction < 0) adjacent(-1)
        if (previousValue == max && direction > 0) adjacent(1)
    }

    private fun configureReadingMode() {
        if (state.preferences.readerMode != "page") return
        editor.putClientProperty("mixn.readerMode", "page")
        editor.addMouseWheelListener { event ->
            val bar = scroll.verticalScrollBar
            val step = bar.visibleAmount.coerceAtLeast(1)
            val max = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)
            bar.value = (bar.value + event.wheelRotation * step).coerceIn(0, max)
            event.consume()
        }
    }

    private fun adjacent(delta: Int) {
        val index = chapters.indexOfFirst { it.remoteId == current.remoteId }
        val target = chapters.getOrNull(index + delta) ?: return
        if (target.locked) { progressLabel.text = "下一章已锁定，请在目录中解锁"; return }
        load(target)
    }

    private fun load(chapter: DesktopChapter) {
        current = chapter
        titleLabel.text = chapter.title
        val index = chapters.indexOfFirst { it.remoteId == chapter.remoteId }
        previous.isEnabled = index > 0
        next.isEnabled = index >= 0 && index < chapters.lastIndex
        progressLabel.text = "正在加载正文…"
        object : SwingWorker<DesktopChapterContent, Unit>() {
            private var failure: Throwable? = null
            override fun doInBackground(): DesktopChapterContent = runCatching { source.chapter(book.remoteId, chapter.remoteId) }
                .onFailure { failure = it }.getOrNull() ?: throw failure ?: IllegalStateException("正文加载失败")

            override fun done() {
                val content = runCatching { get() }.getOrElse { error -> progressLabel.text = error.message ?: "正文加载失败"; return }
                val body = content.bodyHtml.ifBlank { content.bodyText.split("\n").joinToString("\n") { "<p>${escapeHtml(it)}</p>" } }
                val preferences = this@ReaderDialog.state.preferences
                val background = this@ReaderDialog.palette.css(this@ReaderDialog.palette.background)
                val text = this@ReaderDialog.palette.css(this@ReaderDialog.palette.text)
                val horizontalPadding = preferences.horizontalPadding
                val lineHeight = "%.2f".format(java.util.Locale.ROOT, preferences.lineHeight)
                editor.text = "<html><head><style>html,body{background:$background;color:$text;}body{font-family:${preferences.fontName};font-size:${preferences.fontSize}pt;line-height:$lineHeight;max-width:760px;margin:0 auto;padding:18px ${horizontalPadding}px 72px;color:$text;background:$background;}p{margin:0 0 1.18em;text-indent:2em;}h1,h2,h3{text-align:center;margin:0 0 1.5em;font-weight:600;}img{display:block;max-width:100%;height:auto;margin:1.25em auto;border-radius:4px;}</style></head><body>$body</body></html>"
                editor.caretPosition = 0
                saveOffline.isEnabled = true
                progressBar.value = 0
                progressLabel.text = "${chapter.title} · 0%"
                restoreProgress(chapter)
            }
        }.execute()
    }

    private fun restoreProgress(chapter: DesktopChapter) {
        val saved = state.progress.readState(book)?.takeIf { it.chapterId == chapter.remoteId } ?: return
        SwingUtilities.invokeLater {
            val bar = scroll.verticalScrollBar
            val max = (bar.maximum - bar.visibleAmount).coerceAtLeast(0)
            bar.value = (max * saved.percent / 100).coerceIn(0, max)
            progressBar.value = saved.percent
            progressLabel.text = "${chapter.title} · ${saved.percent}%"
        }
    }

    private fun downloadCurrent() {
        saveOffline.isEnabled = false
        runAsync("下载本章") {
            val content = source.chapter(book.remoteId, current.remoteId)
            state.offline.save(book, content)
        }.onSuccess { saveOffline.isEnabled = true; progressLabel.text = "已保存离线章节：${current.title}" }
            .onFailure { saveOffline.isEnabled = true; progressLabel.text = it.message ?: "保存失败" }
    }

    private fun exportEpub() {
        val chooser = JFileChooser().apply { dialogTitle = "导出 EPUB"; selectedFile = File("${book.title}.epub") }
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        runAsync("导出 EPUB") { state.offline.exportEpub(book, chapters, chooser.selectedFile) }
            .onSuccess { progressLabel.text = "已导出：${chooser.selectedFile.name}" }
            .onFailure { progressLabel.text = it.message ?: "导出失败" }
    }
}

private fun <T> runAsync(label: String, action: () -> T): DesktopAsyncResult<T> {
    val result = DesktopAsyncResult<T>(label)
    object : SwingWorker<T, Unit>() {
        override fun doInBackground(): T = action()
        override fun done() { runCatching { get() }.onSuccess(result::success).onFailure(result::failure) }
    }.execute()
    return result
}

private class DesktopAsyncResult<T>(private val label: String) {
    private var successHandler: ((T) -> Unit)? = null
    private var failureHandler: ((Throwable) -> Unit)? = null
    private var value: Result<T>? = null
    fun onSuccess(handler: (T) -> Unit): DesktopAsyncResult<T> { successHandler = handler; value?.onSuccess(handler); return this }
    fun onFailure(handler: (Throwable) -> Unit): DesktopAsyncResult<T> { failureHandler = handler; value?.onFailure(handler); return this }
    fun success(value: T) { this.value = Result.success(value); successHandler?.invoke(value) }
    fun failure(error: Throwable) { value = Result.failure(error); failureHandler?.invoke(error) }
}

private fun profileText(profile: DesktopProfile): String = buildString {
    append("${profile.displayName.ifBlank { "用户" }} · ")
    append(profile.balance?.let { "余额 $it" } ?: "余额未知")
    if (profile.levelLabel != null) append(" · ${profile.levelLabel}")
    if (profile.extra.isNotEmpty()) append(" · ${profile.extra.entries.joinToString("，") { "${it.key} ${it.value}" }}")
}

private fun escapeHtml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun doubleClick(action: () -> Unit) = object : MouseAdapter() {
    override fun mouseClicked(event: MouseEvent) { if (event.clickCount == 2 && event.button == MouseEvent.BUTTON1) action() }
}

private fun header(
    title: String,
    subtitle: String? = null,
    action: (() -> Unit)? = null,
    actionIcon: String = "search",
) = JPanel(BorderLayout(10, 0)).apply {
    background = MIXN_BG
    add(JPanel(BorderLayout(0, 2)).apply {
        background = MIXN_BG
        add(JLabel(title).apply {
            font = font.deriveFont(Font.BOLD, 25f)
            foreground = MIXN_TEXT
        }, BorderLayout.NORTH)
        subtitle?.let { add(JLabel(it).apply { foreground = MIXN_MUTED; font = font.deriveFont(Font.PLAIN, 12f) }, BorderLayout.SOUTH) }
    }, BorderLayout.CENTER)
    action?.let {
        add(JButton(NavIcon(actionIcon)).apply {
            toolTipText = if (actionIcon == "search") "搜索" else "返回发现"
            preferredSize = Dimension(38, 38)
            isFocusPainted = false
            background = MIXN_BG
            border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
            addActionListener { it() }
        }, BorderLayout.EAST)
    }
    add(JSeparator().apply { foreground = MIXN_BORDER }, BorderLayout.SOUTH)
}

private class ChapterCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean) =
        super.getListCellRendererComponent(list, value, index, selected, focus).apply {
            val chapter = value as? DesktopChapter
            text = if (chapter == null) value?.toString().orEmpty() else "${chapter.order}. ${chapter.title}${if (chapter.locked) "  [锁定${chapter.coinPrice?.let { "·${it}币" }.orEmpty()}]" else ""}"
            foreground = when {
                selected -> Color.WHITE
                chapter?.locked == true -> Color(0xB54708)
                else -> MIXN_TEXT
            }
            background = if (selected) MIXN_PRIMARY else Color.WHITE
            border = BorderFactory.createEmptyBorder(10, 14, 10, 14)
        }
}
