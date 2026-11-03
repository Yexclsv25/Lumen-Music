package com.yexclsv.localfiles

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.media.MediaMetadataRetriever
import android.provider.OpenableColumns
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.content.pm.PackageInfoCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.slider.Slider
import com.yexclsv.localfiles.audio.AudioFx
import com.yexclsv.localfiles.data.*
import com.yexclsv.localfiles.discord.DiscordPresence
import com.yexclsv.localfiles.update.UpdateInfo
import com.yexclsv.localfiles.update.UpdateResult
import com.yexclsv.localfiles.update.Updater
import kotlinx.coroutines.CancellationException
import com.yexclsv.localfiles.databinding.ActivityMainBinding
import com.yexclsv.localfiles.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding: ActivityMainBinding
        get() = checkNotNull(_binding) { "Activity has been destroyed" }

    private val vm: PlayerViewModel by viewModels()

    private lateinit var trackAdapter: TrackAdapter
    private lateinit var detailAdapter: TrackAdapter
    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var sheet: ActionSheet
    private lateinit var settingsUi: SettingsUi

    private var topInset = 0
    private var bottomInset = 0
    private var page = 0
    private var searching = false
    private var hasMini = false
    private var playerOpen = false
    private var accent = Accents.colors[0]
    private var playerAccent = accent
    private var artColors: ArtColors? = null
    private var lastUi = PlayerUi()
    private var lastTrackKey: String? = null
    private var libraryCount = 0
    private var playlistRowsNow: List<PlaylistRow> = emptyList()
    private var artJob: Job? = null
    private var ambientBlurSet = false

    // progress ticker
    private var ticking = false
    private var seeking = false
    private var lastSec = -1L
    private var lastMiniMs = 0L

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            updateProgress(frameTimeNanos / 1_000_000, false)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    // ---------- activity results ----------
    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { persist(it) }
            vm.addFiles(uris)
        }
    }
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(uri)
            vm.addFolder(uri)
        }
    }
    private var coverTargetKey: String? = null
    private val pickCover = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = coverTargetKey
        if (uri != null && k != null) {
            vm.setCover(k, uri)
            toast("Cover updated")
        }
    }
    private val backupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(vm.store.exportJson().toByteArray()) }
                toast("Backup saved")
            } catch (_: Exception) {
                toast("Couldn't save the backup")
            }
        }
    }
    private val restoreLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
            if (text != null && vm.store.importJson(text)) {
                toast("Backup restored")
                vm.scan()
            } else toast("That file isn't a valid Lumen backup")
        }
    }
    private var pendingApk: java.io.File? = null
    private lateinit var lyricsPanel: LyricsPanel
    private var showLyrics = false
    private var lyricsKey: String? = null
    private var pendingShortcut: String? = null
    private var chipsPage = -1
    private var introStart = 0L
    private var lyricsTargetKey: String? = null
    private val pickLyrics = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val k = lyricsTargetKey
        if (uri != null && k != null) {
            val text = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) {
                null
            }
            if (!text.isNullOrBlank()) {
                vm.store.saveLyrics(k, text)
                reloadLyricsIfCurrent(k)
                toast("Lyrics added")
            } else toast("Couldn't read that file")
        }
    }

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.scan()
        updateEmpty()
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
        }
    }

    private fun audioPerm() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, audioPerm()) == PackageManager.PERMISSION_GRANTED

    private fun requestPerms() {
        val list = ArrayList<String>()
        if (!hasAudioPermission()) list.add(audioPerm())
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) list.add(Manifest.permission.POST_NOTIFICATIONS)
        if (list.isNotEmpty()) permLauncher.launch(list.toTypedArray())
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        _binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupIntro(savedInstanceState == null)

        sheet = ActionSheet(binding.sheetHost, { accent }, { bottomInset })

        setupInsets()
        setupGlass()
        setupLists()
        setupTopBar()
        setupTabs()
        setupMini()
        setupPlayer()
        setupSettings()
        setupBack()
        observe()
        DiscordPresence.attach(this)

        requestPerms()
        handleIntent(intent)
        showLastCrash()
        showWhatsNew()
        autoCheckUpdates()
    }

    private fun showLastCrash() {
        val f = java.io.File(filesDir, "crash.txt")
        if (!f.exists()) return
        val trace = try { f.readText() } catch (_: Exception) { "" }
        f.delete()
        if (trace.isBlank()) return
        val tv = TextView(this).apply {
            text = trace.take(6000)
            textSize = 11f
            setTextIsSelectable(true)
            setTextColor(Color.WHITE)
            setPadding(20.dp, 10.dp, 20.dp, 10.dp)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Last crash")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Copy") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("crash", trace))
            }
            .setNegativeButton("Close", null)
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        DiscordPresence.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW && i.data != null && i.scheme?.startsWith("discord-") != true) {
            val u = i.data!!
            persist(u)
            vm.playExternal(u)
            i.data = null
        }
        when (i?.action) {
            "com.yexclsv.localfiles.SHUFFLE" -> pendingShortcut = "shuffle"
            "com.yexclsv.localfiles.SEARCH" -> binding.root.post {
                if (page != 0) { binding.tabBar.select(0, false); switchPage(0) }
                if (!searching) openSearch()
            }
        }
        if (i?.action?.startsWith("com.yexclsv.localfiles.") == true) i.action = null
    }

    override fun onResume() {
        super.onResume()
        pendingApk?.let {
            if (Updater.canInstall(this)) {
                pendingApk = null
                Updater.install(this, it)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        updateTicker()
    }

    override fun onStop() {
        ticking = false
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        _binding = null
    }

    // ---------- insets & layout ----------
    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainRoot) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            topInset = bars.top
            bottomInset = bars.bottom
            applyLayout()
            insets
        }
    }

    private fun barHeight(chips: Boolean) = (10 + 56 + 10).dp + if (chips) 44.dp else 0

    private fun applyLayout() {
        val b = binding
        (b.topBar.layoutParams as FrameLayout.LayoutParams).topMargin = topInset + 12.dp
        (b.tabBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomInset + 12.dp
        (b.miniPlayer.layoutParams as FrameLayout.LayoutParams).bottomMargin = bottomInset + 12.dp + 64.dp + 8.dp
        (b.pages.layoutParams as FrameLayout.LayoutParams).topMargin = topInset
        b.pages.requestLayout()
        b.topBar.requestLayout()
        b.tabBar.requestLayout()
        b.miniPlayer.requestLayout()
        b.player.playerContent.setPadding(0, topInset, 0, bottomInset + 8.dp)
        applyPagePadding()
    }

    private fun applyPagePadding() {
        val b = binding
        val libTop = 12.dp + barHeight(true) + 10.dp
        val otherTop = 12.dp + barHeight(false) + 10.dp
        val bottom = bottomInset + 12.dp + 64.dp + 16.dp + if (hasMini) 72.dp else 0
        b.pageLibrary.list.setPadding(0, libTop, 0, bottom)
        b.pageLibrary.emptyState.setPadding(40.dp, libTop, 40.dp, bottom)
        b.pagePlaylists.playlistGrid.setPadding(12.dp, libTop, 12.dp, bottom)
        b.pagePlaylists.detail.setPadding(0, otherTop, 0, 0)
        b.pagePlaylists.detailList.setPadding(0, 0, 0, bottom)
        b.pageSettings.settingsScroll.setPadding(0, otherTop, 0, bottom)
    }

    private fun setupGlass() {
        val b = binding
        b.topBar.backdrop = b.backdrop
        b.miniPlayer.backdrop = b.backdrop
        b.tabBar.backdrop = b.backdrop
        b.player.controlsGlass.backdrop = b.player.ambientHost
        b.miniProgress.pivotX = 0f
    }

    // ---------- lists ----------
    private fun setupLists() {
        val b = binding
        trackAdapter = TrackAdapter(lifecycleScope, { accent },
            onClick = { _, pos ->
                vm.play(trackAdapter.currentList.map { it.track }, pos)
            },
            onMore = { showTrackMenu(it.track, null) })
        b.pageLibrary.list.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = trackAdapter
            setHasFixedSize(true)
        }

        detailAdapter = TrackAdapter(lifecycleScope, { accent },
            onClick = { _, pos ->
                vm.play(detailAdapter.currentList.map { it.track }, pos)
            },
            onMore = { showTrackMenu(it.track, vm.detailId.value) })
        b.pagePlaylists.detailList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = detailAdapter
        }

        playlistAdapter = PlaylistAdapter(lifecycleScope, { accent },
            onClick = { openPlaylist(it.id) },
            onLong = { showPlaylistMenu(it) })
        b.pagePlaylists.playlistGrid.apply {
            layoutManager = GridLayoutManager(context, 2)
            adapter = playlistAdapter
        }

        b.pagePlaylists.btnPlayAll.pressBounce(0.95f)
        b.pagePlaylists.btnShuffleAll.pressBounce(0.95f)
        b.pagePlaylists.btnPlayAll.setOnClickListener {
            vm.setShuffle(false)
            vm.play(detailAdapter.currentList.map { it.track }, 0)
        }
        b.pagePlaylists.btnShuffleAll.setOnClickListener {
            vm.shuffleAll(detailAdapter.currentList.map { it.track })
        }

        b.pageLibrary.btnGrant.pressBounce(0.95f)
        b.pageLibrary.btnPickFiles.pressBounce(0.95f)
        b.pageLibrary.btnGrant.setOnClickListener { requestPerms() }
        b.pageLibrary.btnPickFiles.setOnClickListener { showAddMenu() }
    }

    // ---------- top bar ----------
    private fun setupTopBar() {
        val b = binding
        listOf(b.btnBack, b.btnSearch, b.btnAdd).forEach { it.pressBounce(0.88f) }
        b.btnSearch.setOnClickListener { if (searching) closeSearch() else openSearch() }
        b.btnAdd.setOnClickListener {
            if (page == 0) showAddMenu() else promptName("New playlist", "") { vm.store.createPlaylist(it) }
        }
        b.btnBack.setOnClickListener { closePlaylist() }
        b.searchField.doAfterTextChanged { t ->
            vm.listState.update { it.copy(query = t?.toString().orEmpty()) }
        }
        buildChips()
        updateTopBar()
    }

    private fun buildChips() {
        val row = binding.chipRow
        row.removeAllViews()
        fun chip(label: String, iconRes: Int = 0, onClick: () -> Unit): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(16.dp, 0, 16.dp, 0)
            if (iconRes != 0) {
                setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
                compoundDrawablePadding = 6.dp
            }
            setOnClickListener { it.tick(); onClick() }
            pressBounce(0.92f)
            row.addView(this, android.widget.LinearLayout.LayoutParams(-2, 34.dp).apply { marginEnd = 8.dp })
        }
        chip("Shuffle", R.drawable.ic_shuffle) { vm.shuffleAll(trackAdapter.currentList.map { it.track }) }.tag = "shuffle"
        chip("All") { vm.listState.update { it.copy(filter = LibraryFilter.ALL) } }.tag = LibraryFilter.ALL
        chip("Favorites") { vm.listState.update { it.copy(filter = LibraryFilter.FAVORITES) } }.tag = LibraryFilter.FAVORITES
        chip("Added") { vm.listState.update { it.copy(filter = LibraryFilter.RECENT) } }.tag = LibraryFilter.RECENT
        chip("Top") { vm.listState.update { it.copy(filter = LibraryFilter.MOST_PLAYED) } }.tag = LibraryFilter.MOST_PLAYED
        chip("History") { vm.listState.update { it.copy(filter = LibraryFilter.HISTORY) } }.tag = LibraryFilter.HISTORY
        chip("Sort", R.drawable.ic_sort) { showSortMenu() }.tag = "sort"
        refreshChips()
    }

    private fun refreshChips() {
        if (chipsPage != 0) return
        val ls = vm.listState.value
        for (i in 0 until binding.chipRow.childCount) {
            val c = binding.chipRow.getChildAt(i) as TextView
            val tag = c.tag
            val on = tag == ls.filter
            val shuffle = tag == "shuffle"
            val fg = when {
                shuffle -> 0xFF101014.toInt()
                on -> 0xFF101014.toInt()
                else -> Color.WHITE
            }
            c.setTextColor(fg)
            c.compoundDrawableTintList = tintList(fg)
            c.background = GradientDrawable().apply {
                cornerRadius = 100f.dp
                setColor(if (shuffle || on) accent else 0x24FFFFFF)
            }
            if (tag == "sort") c.text = "Sort: ${ls.sort.label}"
        }
    }

    private fun openSearch() {
        val b = binding
        searching = true
        b.title.visibility = View.GONE
        b.countBadge.visibility = View.GONE
        b.spacer.visibility = View.GONE
        b.searchField.visibility = View.VISIBLE
        b.btnSearch.setImageResource(R.drawable.ic_close)
        b.searchField.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(b.searchField, 0)
    }

    private fun closeSearch() {
        if (!searching) return
        val b = binding
        searching = false
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(b.searchField.windowToken, 0)
        b.searchField.setText("")
        b.searchField.clearFocus()
        b.searchField.visibility = View.GONE
        b.title.visibility = View.VISIBLE
        b.spacer.visibility = View.VISIBLE
        b.countBadge.visibility = if (page == 2) View.GONE else View.VISIBLE
        b.btnSearch.setImageResource(R.drawable.ic_search)
    }

    private fun buildBrowseChips() {
        val row = binding.chipRow
        row.removeAllViews()
        listOf("Playlists", "Albums", "Artists").forEachIndexed { i, label ->
            row.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(18.dp, 0, 18.dp, 0)
                tag = i
                setOnClickListener {
                    it.tick()
                    vm.browseMode.value = i
                    refreshBrowseChips()
                    updateTopBar()
                }
                pressBounce(0.92f)
            }, android.widget.LinearLayout.LayoutParams(-2, 34.dp).apply { marginEnd = 8.dp })
        }
        refreshBrowseChips()
    }

    private fun refreshBrowseChips() {
        for (i in 0 until binding.chipRow.childCount) {
            val c = binding.chipRow.getChildAt(i) as TextView
            val on = c.tag == vm.browseMode.value
            c.setTextColor(if (on) 0xFF101014.toInt() else Color.WHITE)
            c.background = GradientDrawable().apply {
                cornerRadius = 100f.dp
                setColor(if (on) accent else 0x24FFFFFF)
            }
        }
    }

    private fun updateTopBar() {
        val b = binding
        val detail = page == 1 && vm.detailId.value != null
        if (page <= 1 && chipsPage != page) {
            chipsPage = page
            if (page == 0) buildChips() else buildBrowseChips()
        }
        b.title.text = when {
            page == 0 -> "Library"
            page == 1 && detail -> playlistRowsNow.firstOrNull { it.id == vm.detailId.value }?.name ?: "Playlist"
            page == 1 -> when (vm.browseMode.value) { 0 -> "Playlists"; 1 -> "Albums"; else -> "Artists" }
            else -> "Settings"
        }
        if (!searching) {
            b.countBadge.visibility = if (page == 2) View.GONE else View.VISIBLE
            b.countBadge.text = when {
                page == 0 -> libraryCount.toString()
                detail -> detailAdapter.itemCount.toString()
                else -> playlistRowsNow.size.toString()
            }
        }
        b.btnSearch.visibility = if (page == 0) View.VISIBLE else View.GONE
        b.btnAdd.visibility = if (page == 0 || (page == 1 && !detail && vm.browseMode.value == 0)) View.VISIBLE else View.GONE
        b.btnBack.visibility = if (detail) View.VISIBLE else View.GONE
        if (page == 0 || (page == 1 && !detail)) {
            if (b.chipScroll.visibility != View.VISIBLE) {
                b.chipScroll.alpha = 0f
                b.chipScroll.visibility = View.VISIBLE
                b.chipScroll.animate().alpha(1f).setDuration(220).start()
            }
        } else {
            b.chipScroll.animate().cancel()
            b.chipScroll.visibility = View.GONE
        }
    }

    // ---------- tabs / pages ----------
    private fun pageView(i: Int): View = when (i) {
        0 -> binding.pageLibrary.root
        1 -> binding.pagePlaylists.root
        else -> binding.pageSettings.root
    }

    private fun setupTabs() {
        val t = binding.tabBar
        t.setTabs(listOf(R.drawable.ic_music_note to "Library", R.drawable.ic_queue_music to "Browse", R.drawable.ic_settings to "Settings"))
        t.onSelected = { switchPage(it) }
    }

    private fun switchPage(i: Int) {
        if (i == page) return
        closeSearch()
        if (i == 2) settingsUi.render()
        val old = pageView(page)
        val new = pageView(i)
        val dir = if (i > page) 1 else -1
        page = i
        updateTopBar()
        new.animate().cancel()
        new.alpha = 0f
        new.translationX = 36.dp * dir.toFloat()
        new.visibility = View.VISIBLE
        new.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        old.animate().cancel()
        old.animate().alpha(0f).translationX(-36.dp * dir.toFloat()).setDuration(220).withEndAction {
            if (pageView(page) !== old) {
                old.visibility = View.GONE
                old.translationX = 0f
            }
        }.start()
    }

    // ---------- playlists ----------
    private fun openPlaylist(id: String) {
        vm.detailId.value = id
        val b = binding.pagePlaylists
        updateTopBar()
        b.detail.alpha = 0f
        b.detail.visibility = View.VISIBLE
        b.detail.animate().alpha(1f).setDuration(260).start()
    }

    private fun closePlaylist() {
        if (vm.detailId.value == null) return
        vm.detailId.value = null
        val b = binding.pagePlaylists
        updateTopBar()
        b.detail.animate().alpha(0f).setDuration(200).withEndAction {
            if (vm.detailId.value == null) b.detail.visibility = View.GONE
        }.start()
    }

    private fun showPlaylistMenu(r: PlaylistRow) {
        if (r.isFav || r.id.contains(':')) return
        sheet.show(r.name, listOf(
            ActionSheet.Item("Rename") { promptName("Rename playlist", r.name) { vm.store.renamePlaylist(r.id, it) } },
            ActionSheet.Item("Delete", destructive = true) { vm.store.deletePlaylist(r.id) }
        ), sheetBackdrop())
    }

    private fun promptName(title: String, initial: String, onDone: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            setSingleLine()
            hint = "Name"
        }
        val box = FrameLayout(this).apply {
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(input)
        }
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> onDone(input.text.toString()) }
            .create()
        d.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
    }

    // ---------- menus ----------
    private fun sheetBackdrop(): View = if (playerOpen) binding.player.ambientHost else binding.backdrop

    private fun showAddMenu() {
        sheet.show("Add music", listOf(
            ActionSheet.Item("Add audio files") { pickFiles.launch(arrayOf("audio/*")) },
            ActionSheet.Item("Add a music folder") { pickFolder.launch(null) },
            ActionSheet.Item("Rescan library") { vm.scan() }
        ), sheetBackdrop())
    }

    private fun showSortMenu() {
        val cur = vm.listState.value.sort
        sheet.show("Sort by", SortMode.values().map { m ->
            ActionSheet.Item(m.label, selected = m == cur) {
                vm.listState.update { it.copy(sort = m) }
                refreshChips()
            }
        }, sheetBackdrop())
    }

    private fun showTrackMenu(t: Track, inPlaylist: String?) {
        val fav = t.key in vm.store.favorites.value
        val items = ArrayList<ActionSheet.Item>()
        items += ActionSheet.Item("Play next") { vm.addNext(t); toast("Playing next") }
        items += ActionSheet.Item("Add to queue") { vm.addToQueue(t); toast("Added to queue") }
        items += ActionSheet.Item(if (fav) "Remove from favorites" else "Add to favorites") { vm.toggleFavorite(t.key) }
        items += ActionSheet.Item("Add to playlist…") { showPlaylistPicker(t) }
        items += ActionSheet.Item("Edit info & cover…") { showEditDialog(t) }
        items += ActionSheet.Item("More from ${t.artist}") { searchFor(t.artist) }
        if (t.album.isNotBlank() && t.album != "Unknown album")
            items += ActionSheet.Item("More from this album") { searchFor(t.album) }
        items += ActionSheet.Item("Share") { shareTrack(t) }
        items += ActionSheet.Item("Song info") { showSongInfo(t) }
        items += ActionSheet.Item("Hide from library", destructive = true) {
            vm.store.hide(t.key)
            toast("Hidden. You can restore it in Settings")
        }
        if (inPlaylist != null && inPlaylist != "fav" && !inPlaylist.contains(':'))
            items += ActionSheet.Item("Remove from this playlist", destructive = true) { vm.store.removeFromPlaylist(inPlaylist, t.key) }
        if (!t.fromMediaStore)
            items += ActionSheet.Item("Remove from library", destructive = true) { vm.removeExtra(t.key) }
        sheet.show(t.title, items, sheetBackdrop())
    }

    private fun showEditDialog(t: Track) {
        fun field(hint: String, v: String) = EditText(this).apply {
            setText(v)
            this.hint = hint
            setSingleLine()
        }
        fun button(label: String) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = android.view.Gravity.CENTER
            setBackgroundResource(R.drawable.bg_input)
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        }
        val title = field("Title", t.title)
        val artist = field("Artist", t.artist)
        val album = field("Album", t.album)
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(title)
            addView(artist)
            addView(album)
        }
        val gap = android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = 12.dp }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Edit song")
            .setView(android.widget.ScrollView(this).apply { addView(col) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ -> vm.editTrack(t.key, title.text.toString(), artist.text.toString(), album.text.toString()) }
            .create()
        col.addView(button("Choose cover image").apply {
            setOnClickListener { coverTargetKey = t.key; pickCover.launch(arrayOf("image/*")) }
        }, gap)
        col.addView(button("Add lyrics from file (.lrc / .txt)").apply {
            setOnClickListener { lyricsTargetKey = t.key; pickLyrics.launch(arrayOf("*/*")) }
        }, gap)
        col.addView(button("Paste lyrics").apply {
            setOnClickListener { dialog.dismiss(); promptLyrics(t) }
        }, gap)
        if (vm.store.readLyrics(t.key) != null) col.addView(button("Remove lyrics").apply {
            setOnClickListener {
                vm.store.deleteLyrics(t.key)
                reloadLyricsIfCurrent(t.key)
                toast("Lyrics removed")
                dialog.dismiss()
            }
        }, gap)
        if (t.coverPath != null) col.addView(button("Remove custom cover").apply {
            setOnClickListener { vm.removeCover(t.key); toast("Cover removed"); dialog.dismiss() }
        }, gap)
        col.addView(button("Reset all edits").apply {
            setOnClickListener { vm.resetEdits(t.key); toast("Reset"); dialog.dismiss() }
        }, gap)
        dialog.show()
    }

    private fun showPlaylistPicker(t: Track) {
        val items = ArrayList<ActionSheet.Item>()
        items += ActionSheet.Item("New playlist…") {
            promptName("New playlist", "") { name ->
                val p = vm.store.createPlaylist(name)
                vm.store.addToPlaylist(p.id, t.key)
                toast("Added to ${p.name}")
            }
        }
        vm.store.playlists.value.forEach { p ->
            items += ActionSheet.Item(p.name, selected = t.key in p.keys) {
                vm.store.addToPlaylist(p.id, t.key)
                toast("Added to ${p.name}")
            }
        }
        sheet.show("Add to playlist", items, sheetBackdrop())
    }

    private fun showSleepMenu() {
        val opts = listOf(0 to "Off", 5 to "5 minutes", 10 to "10 minutes", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", 90 to "90 minutes")
        val active = vm.sleepUntil.value > 0
        val eot = vm.sleepUntil.value == Long.MAX_VALUE
        sheet.show("Sleep timer", listOf(ActionSheet.Item("End of this song", selected = eot) {
            vm.setSleepEndOfTrack()
            toast("Pausing after this song")
        }) + opts.map { (m, label) ->
            ActionSheet.Item(label, selected = m == 0 && !active && !eot) {
                vm.setSleep(m)
                if (m > 0) toast("Pausing in $label")
            }
        }, sheetBackdrop())
    }

    private fun showSpeedMenu() {
        val cur = lastUi.speed
        val opts = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        sheet.show("Playback speed", opts.map { s ->
            ActionSheet.Item(speedLabel(s), selected = abs(s - cur) < 0.01f) { vm.setSpeed(s) }
        }, sheetBackdrop())
    }

    private fun showQueue() {
        val q = vm.upNext()
        val items = ArrayList<ActionSheet.Item>()
        items += ActionSheet.Item("Save queue as playlist…") { saveQueueAsPlaylist() }
        if (q.isNotEmpty()) items += ActionSheet.Item("Clear upcoming songs", destructive = true) { vm.clearUpcoming() }
        q.forEach { (i, t) -> items += ActionSheet.Item("${t.title} — ${t.artist}") { vm.jumpTo(i) } }
        sheet.show("Up next", items, sheetBackdrop())
    }

    private fun saveQueueAsPlaylist() {
        val tracks = vm.queueTracks()
        if (tracks.isEmpty()) {
            toast("The queue is empty")
            return
        }
        promptName("Save queue as playlist", "My queue") { name ->
            val p = vm.store.createPlaylist(name)
            vm.store.addAllToPlaylist(p.id, tracks.map { it.key })
            toast("Saved ${tracks.size} songs to ${p.name}")
        }
    }

    private fun speedLabel(s: Float) = if (s == s.toInt().toFloat()) "${s.toInt()}x" else "${s}x"
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    // ---------- mini player ----------
    private fun setupMini() {
        val b = binding
        b.miniPlay.pressBounce(0.85f)
        b.miniNext.pressBounce(0.85f)
        b.miniPlay.setOnClickListener { it.tick(); vm.togglePlay() }
        b.miniNext.setOnClickListener { it.tick(); vm.next() }
        b.miniArt.roundCorners(15f.dp)

        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { openPlayer(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vy) > abs(vx) && vy < -700) openPlayer()
                else if (vx < -900) { b.miniPlayer.tick(); vm.next() }
                else if (vx > 900) { b.miniPlayer.tick(); vm.previous() }
                return true
            }
        })
        b.miniPlayer.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { v.spring(DynamicAnimation.SCALE_X, 0.97f, 900f, 0.7f); v.spring(DynamicAnimation.SCALE_Y, 0.97f, 900f, 0.7f) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { v.spring(DynamicAnimation.SCALE_X, 1f, 420f, 0.5f); v.spring(DynamicAnimation.SCALE_Y, 1f, 420f, 0.5f) }
            }
            gd.onTouchEvent(e)
        }
    }

    private fun setMiniVisible(show: Boolean) {
        hasMini = show
        val m = binding.miniPlayer
        applyPagePadding()
        if (show) {
            m.visibility = View.VISIBLE
            m.translationY = 90.dp.toFloat()
            m.alpha = 0f
            m.animate().alpha(1f).setDuration(200).start()
            m.spring(DynamicAnimation.TRANSLATION_Y, 0f, 380f, 0.78f)
        } else {
            m.animate().alpha(0f).setDuration(180).withEndAction { if (!hasMini) m.visibility = View.GONE }.start()
        }
    }

    // ---------- full player ----------
    private fun setupPlayer() {
        val p = binding.player
        p.playerArt.roundCorners(28f.dp)
        p.playerTitle.isSelected = true
        listOf(p.btnClose, p.btnMore, p.btnFav, p.btnPrev, p.btnNext, p.btnShuffle, p.btnSpeed, p.btnSleep, p.btnQueue, p.btnRepeat)
            .forEach { it.pressBounce(0.86f) }
        p.btnPlay.pressBounce(0.9f)

        p.btnClose.setOnClickListener { closePlayer(0f) }
        p.btnPlay.setOnClickListener { it.tick(); vm.togglePlay() }
        p.btnNext.setOnClickListener { it.tick(); vm.next() }
        p.btnPrev.setOnClickListener { it.tick(); vm.previous() }
        p.btnShuffle.setOnClickListener { it.tick(); vm.toggleShuffle() }
        p.btnRepeat.setOnClickListener { it.tick(); vm.cycleRepeat() }
        p.btnSpeed.setOnClickListener { showSpeedMenu() }
        p.btnSleep.setOnClickListener { showSleepMenu() }
        p.btnQueue.setOnClickListener { showQueue() }
        p.btnMore.setOnClickListener { lastUi.current?.let { showTrackMenu(it, null) } }
        p.btnFav.setOnClickListener {
            val t = lastUi.current ?: return@setOnClickListener
            it.tick()
            vm.toggleFavorite(t.key)
            it.scaleX = 0.6f; it.scaleY = 0.6f
            it.spring(DynamicAnimation.SCALE_X, 1f, 500f, 0.35f)
            it.spring(DynamicAnimation.SCALE_Y, 1f, 500f, 0.35f)
        }

        lyricsPanel = LyricsPanel(p.lyricsScroll, p.lyricsContainer) { ms -> vm.seekTo(ms) }
        p.btnLyrics.pressBounce(0.86f)
        p.btnLyrics.setOnClickListener { it.tick(); toggleLyrics() }

        val artGestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean {
                likeWithBurst()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 900) {
                    val dir = if (vx < 0) -1 else 1
                    p.playerArt.tick()
                    if (dir < 0) vm.next() else vm.previous()
                    p.playerArt.translationX = dir * 70.dp.toFloat()
                    p.playerArt.spring(DynamicAnimation.TRANSLATION_X, 0f, 380f, 0.7f)
                }
                return true
            }
        })
        p.playerArt.setOnTouchListener { _, e -> artGestures.onTouchEvent(e) }

        p.seek.listener = object : LiquidSeekBar.Listener {
            override fun onStart() { seeking = true }
            override fun onChange(fraction: Float) {
                val dur = max(1L, lastUi.durationMs)
                val pos = (fraction * dur).toLong()
                p.timeCur.text = fmtTime(pos)
                p.timeRemain.text = "-" + fmtTime(dur - pos)
            }
            override fun onStop(fraction: Float) {
                vm.seekTo((fraction * max(1L, lastUi.durationMs)).toLong())
                seeking = false
            }
        }

        p.root.onRelease = { dismiss, vy ->
            if (dismiss) closePlayer(vy) else p.root.spring(DynamicAnimation.TRANSLATION_Y, 0f, 500f, 0.85f, vy)
        }
    }

    private fun openPlayer() {
        if (playerOpen || lastUi.current == null) return
        playerOpen = true
        val p = binding.player.root
        p.translationY = binding.mainRoot.height.toFloat()
        p.visibility = View.VISIBLE
        p.spring(DynamicAnimation.TRANSLATION_Y, 0f, 360f, 0.9f)
        bindPlayer(lastUi)
        updateProgress(System.nanoTime() / 1_000_000, true)
    }

    private fun closePlayer(velocity: Float) {
        if (!playerOpen) return
        playerOpen = false
        sheet.dismiss(false)
        val p = binding.player.root
        p.spring(DynamicAnimation.TRANSLATION_Y, binding.mainRoot.height.toFloat(), 380f, 1f, velocity) {
            if (!playerOpen) p.visibility = View.GONE
        }
    }

    private fun bindPlayer(u: PlayerUi) {
        val p = binding.player
        val t = u.current ?: return
        p.playerTitle.text = t.title
        p.playerArtist.text = t.artist
        p.btnPlay.setImageResource(if (u.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val fav = t.key in vm.store.favorites.value
        p.btnFav.setImageResource(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        p.btnFav.imageTintList = tintList(if (fav) playerAccent else Color.WHITE)
        p.btnShuffle.imageTintList = tintList(if (u.shuffle) playerAccent else 0x99FFFFFF.toInt())
        p.btnRepeat.setImageResource(if (u.repeat == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        p.btnRepeat.imageTintList = tintList(if (u.repeat == Player.REPEAT_MODE_OFF) 0x99FFFFFF.toInt() else playerAccent)
        p.btnSpeed.text = speedLabel(u.speed)
        p.btnSpeed.setTextColor(if (abs(u.speed - 1f) < 0.01f) 0x99FFFFFF.toInt() else playerAccent)
        p.btnSleep.imageTintList = tintList(if (vm.sleepUntil.value > 0) playerAccent else 0x99FFFFFF.toInt())
        p.btnQueue.imageTintList = tintList(0x99FFFFFF.toInt())
        p.btnLyrics.imageTintList = tintList(if (showLyrics) playerAccent else 0x99FFFFFF.toInt())
        p.btnPrev.imageTintList = tintList(Color.WHITE)
        p.btnNext.imageTintList = tintList(Color.WHITE)
        val s = if (u.isPlaying) 1f else 0.86f
        p.playerArt.spring(DynamicAnimation.SCALE_X, s, 300f, 0.72f)
        p.playerArt.spring(DynamicAnimation.SCALE_Y, s, 300f, 0.72f)
    }

    private fun applyAmbient() {
        val s = vm.store.settings.value
        val ac = artColors
        val dyn = s.dynamicColor && ac != null
        playerAccent = if (dyn) ac!!.accent else accent
        binding.aurora.setColors(
            if (dyn) ac!!.c1 else accent,
            if (dyn) ac!!.c2 else mix(accent, 0xFF2A6CFF.toInt(), 0.5f)
        )
        val p = binding.player
        p.seek.accent = playerAccent
        p.btnPlay.backgroundTintList = tintList(playerAccent)
        p.playerArtist.setTextColor(playerAccent)
        binding.miniProgress.backgroundTintList = tintList(playerAccent)
        lyricsPanel.setAccent(playerAccent)
        bindPlayer(lastUi)
    }

    private fun setAmbient(bmp: Bitmap?) {
        val iv = binding.player.ambient
        if (bmp == null) {
            iv.setImageDrawable(null)
            return
        }
        if (Build.VERSION.SDK_INT >= 31) {
            iv.setImageBitmap(bmp)
            if (!ambientBlurSet) {
                iv.setRenderEffect(RenderEffect.createBlurEffect(70f, 70f, Shader.TileMode.CLAMP))
                iv.scaleX = 1.5f
                iv.scaleY = 1.5f
                ambientBlurSet = true
            }
        } else {
            iv.setImageBitmap(Bitmap.createScaledBitmap(bmp.soft(), 16, 16, true))
        }
        iv.alpha = 0f
        iv.animate().alpha(0.9f).setDuration(600).start()
    }

    private fun onTrackChanged(t: Track) {
        binding.miniArt.loadArt(lifecycleScope, t, 140)
        loadLyrics(t)
        binding.player.playerArt.loadArt(lifecycleScope, t, 800)
        artJob?.cancel()
        artJob = lifecycleScope.launch {
            val bmp = ArtLoader.load(applicationContext, t, 400)
            artColors = bmp?.let { ArtPalette.extract(it) }
            setAmbient(bmp)
            applyAmbient()
        }
    }

    // ---------- progress ticker ----------
    private fun updateTicker() {
        val want = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && lastUi.isPlaying
        if (want && !ticking) {
            ticking = true
            Choreographer.getInstance().postFrameCallback(frameCb)
        } else if (!want) {
            ticking = false
            updateProgress(System.nanoTime() / 1_000_000, true)
        }
    }

    private fun updateProgress(nowMs: Long, force: Boolean) {
        val u = lastUi
        val dur = max(1L, u.durationMs)
        val pos = vm.position().coerceIn(0L, dur)
        val p = binding.player
        if (playerOpen && !seeking) {
            p.seek.progress = pos.toFloat() / dur
            val sec = pos / 1000
            if (sec != lastSec || force) {
                lastSec = sec
                p.timeCur.text = fmtTime(pos)
                p.timeRemain.text = "-" + fmtTime(dur - pos)
            }
        }
        if (playerOpen && showLyrics) lyricsPanel.update(pos)
        if (force || nowMs - lastMiniMs > 500) {
            lastMiniMs = nowMs
            binding.miniProgress.scaleX = pos.toFloat() / dur
        }
    }

    // ---------- settings ----------
    private fun setupSettings() {
        settingsUi = SettingsUi(binding.pageSettings.settingsContainer, object : SettingsUi.Callbacks {
            override fun settings() = vm.store.settings.value
            override fun update(block: (Settings) -> Settings) = vm.store.update(block)
            override fun trackCount() = vm.tracks.value.size
            override fun folderCount() = vm.store.extraFolders().size
            override fun sleepUntil() = vm.sleepUntil.value
            override fun rescan() = vm.scan()
            override fun addFolder() { pickFolder.launch(null) }
            override fun addFiles() { pickFiles.launch(arrayOf("audio/*")) }
            override fun clearExtras() = vm.clearExtras()
            override fun sleepMenu() = showSleepMenu()
            override fun version() = versionName()
            override fun checkUpdates() = checkForUpdates(true)
            override fun openEqualizer() = showEqualizer()
            override fun backup() { backupLauncher.launch("Lumen-backup.json") }
            override fun restore() { restoreLauncher.launch(arrayOf("application/json", "*/*")) }
            override fun stats(): Pair<String, String> {
                val p = vm.store.plays.value
                val total = p.values.sumOf { it.count }
                val topKey = p.maxByOrNull { it.value.count }?.key
                val t = vm.tracks.value.firstOrNull { it.key == topKey }
                return "$total plays" to (if (t != null) "Top song: ${t.title} (${p[t.key]?.count}x)" else "Play songs to build your stats")
            }
            override fun resetStats() { vm.store.resetPlays(); toast("Stats cleared"); settingsUi.render() }
            override fun openRepo() = openUrl(Updater.REPO_URL)
            override fun hiddenCount() = vm.store.hidden.value.size
            override fun discordStatus() = DiscordPresence.status
            override fun connectDiscord() {
                DiscordPresence.connect(this@MainActivity) { st ->
                    runOnUiThread {
                        toast(st)
                        settingsUi.render()
                    }
                }
            }
            override fun unhideAll() { vm.store.unhideAll(); toast("Songs restored"); settingsUi.render() }
        })
        settingsUi.render()
    }

    private fun applySettings(s: Settings) {
        val newAccent = Accents.resolve(s.accentIndex, s.customHue)
        val accentChanged = newAccent != accent
        accent = newAccent
        Haptics.enabled = s.haptics
        GlassConfig.update(s.blurDp, s.refraction, s.tintPct)
        val bg = Backgrounds.colors[s.bgIndex.coerceIn(0, Backgrounds.colors.lastIndex)]
        binding.mainRoot.setBackgroundColor(bg)
        binding.backdrop.setBackgroundColor(bg)
        binding.player.root.setBackgroundColor(bg)
        binding.player.ambientHost.setBackgroundColor(bg)
        binding.pagePlaylists.detail.setBackgroundColor(bg)
        binding.tabBar.accent = accent
        if (accentChanged) {
            refreshChips()
            trackAdapter.notifyDataSetChanged()
            detailAdapter.notifyDataSetChanged()
            playlistAdapter.notifyDataSetChanged()
        }
        applyAmbient()
    }

    // ---------- empty state ----------
    private fun updateEmpty() {
        val b = binding.pageLibrary
        val show = libraryCount == 0 && !vm.scanning.value
        b.emptyState.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val noTracks = vm.tracks.value.isEmpty()
        val perm = hasAudioPermission()
        b.emptyTitle.text = if (noTracks) "No music yet" else "Nothing here"
        b.emptyBody.text = when {
            !noTracks -> "Try another search or filter."
            !perm -> "Allow access to find the music on this device, or add files and folders yourself."
            else -> "Nothing found on this device. Add files or a folder to get started."
        }
        b.btnGrant.visibility = if (noTracks && !perm) View.VISIBLE else View.GONE
        b.btnPickFiles.visibility = if (noTracks) View.VISIBLE else View.GONE
    }

    // ---------- observers ----------
    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.rows.collect { rows ->
                        libraryCount = rows.size
                        trackAdapter.submitList(rows)
                        if (pendingShortcut == "shuffle" && rows.isNotEmpty()) {
                            pendingShortcut = null
                            vm.shuffleAll(rows.map { it.track })
                        }
                        if (page == 0 && !searching) binding.countBadge.text = rows.size.toString()
                        updateEmpty()
                    }
                }
                launch {
                    vm.detailRows.collect { rows ->
                        detailAdapter.submitList(rows)
                        binding.pagePlaylists.detailEmpty.visibility =
                            if (rows.isEmpty() && vm.detailId.value != null) View.VISIBLE else View.GONE
                        if (page == 1 && vm.detailId.value != null) binding.countBadge.text = rows.size.toString()
                    }
                }
                launch {
                    vm.browseRows.collect { rows ->
                        playlistRowsNow = rows
                        playlistAdapter.submitList(rows)
                        if (page == 1) updateTopBar()
                    }
                }
                launch { vm.ui.collect { onPlayerUi(it) } }
                launch { vm.errors.collect { Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show() } }
                launch { vm.scanning.collect { updateEmpty() } }
                launch { vm.listState.collect { refreshChips() } }
                launch { vm.tracks.collect { settingsUi.render() } }
                launch { vm.sleepUntil.collect { settingsUi.render(); bindPlayer(lastUi) } }
                launch { vm.store.settings.collect { applySettings(it) } }
            }
        }
    }

    private fun onPlayerUi(u: PlayerUi) {
        lastUi = u
        val t = u.current
        val show = t != null
        if (show != hasMini) setMiniVisible(show)
        if (t != null) {
            binding.miniTitle.text = t.title
            binding.miniArtist.text = t.artist
            binding.miniPlay.setImageResource(if (u.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            val sig = t.key + "|" + t.coverPath
            if (sig != lastTrackKey) {
                lastTrackKey = sig
                onTrackChanged(t)
            }
        } else {
            lastTrackKey = null
            if (playerOpen) closePlayer(0f)
        }
        if (playerOpen) bindPlayer(u)
        updateTicker()
    }

    // ---------- intro ----------
    private fun setupIntro(first: Boolean) {
        val b = binding
        if (!first) {
            b.intro.visibility = View.GONE
            return
        }
        introStart = SystemClock.elapsedRealtime()
        b.introAurora.setColors(accent, mix(accent, 0xFF2A6CFF.toInt(), 0.5f), false)
        b.introLogo.roundCorners(28f.dp)
        b.introBars.color = Color.WHITE
        b.introBars.active = true
        b.introLogo.alpha = 0f
        b.introLogo.scaleX = 0.6f
        b.introLogo.scaleY = 0.6f
        b.introLogo.animate().alpha(1f).setDuration(450).start()
        b.introLogo.spring(DynamicAnimation.SCALE_X, 1f, 260f, 0.5f)
        b.introLogo.spring(DynamicAnimation.SCALE_Y, 1f, 260f, 0.5f)
        listOf<View>(b.introTitle, b.introTag, b.introBars, b.introFoot).forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 24.dp.toFloat()
            v.animate().alpha(1f).translationY(0f).setStartDelay(250L + i * 110L).setDuration(500)
                .setInterpolator(PathInterpolator(0.2f, 0f, 0f, 1f)).start()
        }
        lifecycleScope.launch {
            withTimeoutOrNull(4000) { vm.scanning.first { !it } }
            val wait = 1500L - (SystemClock.elapsedRealtime() - introStart)
            if (wait > 0) delay(wait)
            dismissIntro()
        }
    }

    private fun dismissIntro() {
        val v = binding.intro
        v.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setDuration(480)
            .setInterpolator(PathInterpolator(0.4f, 0f, 0.2f, 1f))
            .withEndAction { v.visibility = View.GONE }.start()
    }

    // ---------- lyrics ----------
    private fun toggleLyrics() {
        showLyrics = !showLyrics
        val p = binding.player
        if (showLyrics) {
            p.lyricsScroll.alpha = 0f
            p.lyricsScroll.visibility = View.VISIBLE
            p.lyricsScroll.animate().alpha(1f).setDuration(220).start()
            p.artHolder.animate().alpha(0f).setDuration(200).start()
        } else {
            p.lyricsScroll.animate().alpha(0f).setDuration(200).withEndAction {
                if (!showLyrics) p.lyricsScroll.visibility = View.GONE
            }.start()
            p.artHolder.animate().alpha(1f).setDuration(220).start()
        }
        bindPlayer(lastUi)
        updateProgress(System.nanoTime() / 1_000_000, true)
    }

    private fun loadLyrics(t: Track) {
        lyricsKey = t.key
        lifecycleScope.launch {
            val txt = withContext(Dispatchers.IO) { vm.store.readLyrics(t.key) }
            if (lyricsKey != t.key) return@launch
            lyricsPanel.setLyrics(txt?.let { LyricsParser.parse(it) }, playerAccent)
            if (playerOpen) updateProgress(System.nanoTime() / 1_000_000, true)
        }
    }

    private fun reloadLyricsIfCurrent(key: String) {
        val cur = lastUi.current ?: return
        if (cur.key == key) loadLyrics(cur)
    }

    private fun promptLyrics(t: Track) {
        val input = EditText(this).apply {
            setText(vm.store.readLyrics(t.key).orEmpty())
            hint = "Paste lyrics here. Lines like [01:23.45] text make them scroll in time."
            minLines = 8
            maxLines = 14
            gravity = android.view.Gravity.TOP
            textSize = 14f
        }
        val box = FrameLayout(this).apply {
            setPadding(24.dp, 8.dp, 24.dp, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Lyrics for ${t.title}")
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString()
                if (text.isBlank()) vm.store.deleteLyrics(t.key) else vm.store.saveLyrics(t.key, text)
                reloadLyricsIfCurrent(t.key)
                toast("Lyrics saved")
            }
            .show()
    }

    private fun likeWithBurst() {
        val t = lastUi.current ?: return
        val nowFav = vm.toggleFavorite(t.key)
        val h = binding.player.heartBurst
        h.setImageResource(if (nowFav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
        h.imageTintList = tintList(if (nowFav) playerAccent else Color.WHITE)
        h.animate().cancel()
        h.visibility = View.VISIBLE
        h.alpha = 1f
        h.scaleX = 0.3f
        h.scaleY = 0.3f
        h.tick()
        h.spring(DynamicAnimation.SCALE_X, 1.15f, 420f, 0.45f)
        h.spring(DynamicAnimation.SCALE_Y, 1.15f, 420f, 0.45f)
        h.animate().alpha(0f).setStartDelay(450).setDuration(300).withEndAction { h.visibility = View.GONE }.start()
    }

    // ---------- song info ----------
    private fun showSongInfo(t: Track) {
        lifecycleScope.launch {
            val lines = withContext(Dispatchers.IO) {
                var mime: String? = null
                var bitrate: String? = null
                var rate: String? = null
                var bits: String? = null
                var size = -1L
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(this@MainActivity, t.uri)
                    mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    if (Build.VERSION.SDK_INT >= 31) {
                        rate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                        bits = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                    }
                } catch (_: Exception) {
                } finally {
                    try { r.release() } catch (_: Exception) {}
                }
                try {
                    contentResolver.query(t.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                        if (c.moveToFirst()) size = c.getLong(0)
                    }
                } catch (_: Exception) {
                }
                val format = when (mime) {
                    "audio/mpeg" -> "MP3"
                    "audio/mp4", "audio/x-m4a" -> "M4A (AAC or Apple Lossless)"
                    "audio/flac", "audio/x-flac" -> "FLAC (lossless)"
                    "audio/x-wav", "audio/wav" -> "WAV (lossless)"
                    "audio/ogg", "application/ogg" -> "Ogg (Vorbis or Opus)"
                    null -> "Unknown"
                    else -> mime
                }
                val out = ArrayList<String>()
                out += "Title: ${t.title}"
                out += "Artist: ${t.artist}"
                out += "Album: ${t.album}"
                if (t.trackNo > 0) out += "Track: ${t.trackNo}"
                out += "Duration: ${fmtTime(t.durationMs)}"
                out += "Format: $format"
                bitrate?.toLongOrNull()?.let { out += "Bitrate: ${it / 1000} kbps" }
                rate?.let { out += "Sample rate: $it Hz" }
                bits?.let { out += "Bit depth: $it-bit" }
                if (size > 0) out += "Size: %.1f MB".format(size / 1048576.0)
                out += "Source: " + (if (t.fromMediaStore) "Device library" else "Added file")
                out
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Song info")
                .setMessage(lines.joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    // ---------- search / share ----------
    private fun searchFor(q: String) {
        if (playerOpen) closePlayer(0f)
        binding.tabBar.select(0, true)
        switchPage(0)
        vm.listState.update { it.copy(filter = LibraryFilter.ALL) }
        if (!searching) openSearch()
        binding.searchField.setText(q)
        binding.searchField.setSelection(q.length)
    }

    private fun shareTrack(t: Track) {
        try {
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "audio/*"
                putExtra(Intent.EXTRA_STREAM, t.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(i, "Share " + t.title))
        } catch (_: Exception) {
            toast("Can't share this file")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("No browser found")
        }
    }

    // ---------- equalizer ----------
    private fun showEqualizer() {
        val info = AudioFx.info()
        if (info == null) {
            toast("Start playing a song first, then open the equalizer")
            return
        }
        val s = vm.store.settings.value
        val saved = s.eqLevels.split(",").mapNotNull { it.trim().toIntOrNull() }
        val levels = MutableList(info.bands) { saved.getOrElse(it) { 0 }.coerceIn(info.minMb, info.maxMb) }
        val col = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24.dp, 8.dp, 24.dp, 0)
        }
        val enable = MaterialSwitch(this).apply {
            text = "Enable equalizer"
            isChecked = s.eqOn
            setTextColor(Color.WHITE)
        }
        fun push() = vm.store.update { it.copy(eqOn = enable.isChecked, eqLevels = levels.joinToString(",")) }
        enable.setOnCheckedChangeListener { _, _ -> push() }
        col.addView(enable)

        val sliders = ArrayList<Slider>()
        val labels = ArrayList<TextView>()
        fun db(mb: Int) = "%+.1f dB".format(mb / 100f)

        if (info.presets.isNotEmpty()) {
            val scroll = android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val row = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.HORIZONTAL }
            info.presets.forEachIndexed { i, name ->
                row.addView(TextView(this).apply {
                    text = name
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    setPadding(14.dp, 8.dp, 14.dp, 8.dp)
                    background = GradientDrawable().apply { cornerRadius = 100f.dp; setColor(0x24FFFFFF) }
                    setOnClickListener {
                        AudioFx.presetLevels(i)?.let { lv ->
                            lv.forEachIndexed { b, v ->
                                if (b < levels.size) {
                                    levels[b] = v.coerceIn(info.minMb, info.maxMb)
                                    sliders[b].value = levels[b].toFloat()
                                    labels[b].text = "${info.freqsHz[b]} Hz   ${db(levels[b])}"
                                }
                            }
                            enable.isChecked = true
                            push()
                        }
                    }
                    pressBounce(0.93f)
                }, android.widget.LinearLayout.LayoutParams(-2, -2).apply { marginEnd = 8.dp })
            }
            scroll.addView(row)
            col.addView(scroll, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = 10.dp; bottomMargin = 6.dp })
        }

        for (b in 0 until info.bands) {
            val label = TextView(this).apply {
                text = "${info.freqsHz[b]} Hz   ${db(levels[b])}"
                setTextColor(0xFFB4B4C2.toInt())
                textSize = 13f
                setPadding(0, 10.dp, 0, 0)
            }
            val sl = Slider(this).apply {
                valueFrom = info.minMb.toFloat()
                valueTo = info.maxMb.toFloat()
                value = levels[b].toFloat()
                trackActiveTintList = tintList(accent)
                thumbTintList = tintList(accent)
                haloTintList = tintList(accent.withAlpha(0x33))
                trackInactiveTintList = tintList(0x26FFFFFF)
                addOnChangeListener { _, v, fromUser ->
                    if (fromUser) {
                        levels[b] = v.toInt()
                        label.text = "${info.freqsHz[b]} Hz   ${db(levels[b])}"
                        if (!enable.isChecked) enable.isChecked = true else push()
                    }
                }
            }
            labels.add(label)
            sliders.add(sl)
            col.addView(label)
            col.addView(sl)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Equalizer")
            .setView(android.widget.ScrollView(this).apply { addView(col) })
            .setNeutralButton("Reset") { _, _ ->
                vm.store.update { it.copy(eqLevels = List(info.bands) { 0 }.joinToString(",")) }
            }
            .setPositiveButton("Done", null)
            .show()
    }

    // ---------- updates ----------
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "2.1"
    } catch (_: Exception) {
        "2.1"
    }

    private fun autoCheckUpdates() {
        val now = System.currentTimeMillis()
        if (vm.store.settings.value.autoUpdate && now - vm.store.getLong("lastCheck") > 12 * 3600_000L) {
            vm.store.putLong("lastCheck", now)
            checkForUpdates(false)
        }
    }

    private fun checkForUpdates(manual: Boolean) {
        if (manual) toast("Checking for updates...")
        lifecycleScope.launch {
            when (val r = Updater.check(versionName())) {
                is UpdateResult.Available ->
                    if (manual || r.info.version != vm.store.getStr("skipVersion")) showUpdateDialog(r.info)
                is UpdateResult.UpToDate -> if (manual) toast("You're on the latest version (v${versionName()})")
                is UpdateResult.Error -> if (manual) toast("Couldn't check for updates: ${r.message}")
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val size = if (info.sizeBytes > 0) "\n\nSize: %.1f MB".format(info.sizeBytes / 1048576.0) else ""
        val tv = TextView(this).apply {
            text = info.notes.trim().ifEmpty { "No release notes." }.take(1200) + size
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(24.dp, 8.dp, 24.dp, 0)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Update available: v${info.version}")
            .setView(android.widget.ScrollView(this).apply { addView(tv) })
            .setPositiveButton(if (info.apkUrl != null) "Update" else "Open release") { _, _ ->
                if (info.apkUrl != null) startDownload(info) else openUrl(info.pageUrl)
            }
            .setNegativeButton("Later", null)
            .setNeutralButton("Skip this version") { _, _ -> vm.store.putStr("skipVersion", info.version) }
            .show()
    }

    private fun startDownload(info: UpdateInfo) {
        val bar = LinearProgressIndicator(this).apply {
            isIndeterminate = false
            setIndicatorColor(accent)
        }
        val label = TextView(this).apply {
            text = "Downloading..."
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 12.dp)
        }
        val box = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(24.dp, 12.dp, 24.dp, 0)
            addView(label)
            addView(bar)
        }
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Updating to v${info.version}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> job?.cancel() }
            .create()
        dialog.show()
        job = lifecycleScope.launch {
            try {
                val f = Updater.download(this@MainActivity, info) { p ->
                    bar.setProgressCompat((p * 100).toInt(), true)
                    label.text = "Downloading... ${(p * 100).toInt()}%"
                }
                dialog.dismiss()
                installApk(f)
            } catch (e: CancellationException) {
                dialog.dismiss()
                throw e
            } catch (e: Exception) {
                dialog.dismiss()
                toast("Update failed: ${e.message}")
            }
        }
    }

    private fun installApk(f: java.io.File) {
        if (Updater.canInstall(this)) {
            Updater.install(this, f)
            return
        }
        pendingApk = f
        MaterialAlertDialogBuilder(this)
            .setTitle("Allow installs")
            .setMessage("Android needs your permission before Lumen can install its own updates. Turn on \"Allow from this source\", then come back.")
            .setPositiveButton("Open settings") { _, _ -> Updater.openInstallPermissionSettings(this) }
            .setNegativeButton("Cancel") { _, _ -> pendingApk = null }
            .show()
    }

    private fun showWhatsNew() {
        val code = try {
            PackageInfoCompat.getLongVersionCode(packageManager.getPackageInfo(packageName, 0))
        } catch (_: Exception) {
            return
        }
        val seen = vm.store.getLong("seenCode", 0L)
        vm.store.putLong("seenCode", code)
        if (seen in 1 until code) {
            MaterialAlertDialogBuilder(this)
                .setTitle("What's new in v${versionName()}")
                .setMessage(
                    if (versionName().startsWith("2.1"))
                        "• Discord Rich Presence: show what you're playing (needs the Discord SDK, see README)\n" +
                        "• Fixed settings content showing above the top bar\n" +
                        "• Fixed duplicated lyrics lines\n" +
                        "• Lyrics fade softly at the edges"
                    else if (versionName().startsWith("2."))
                        "• New animated intro screen\n" +
                        "• Browse by album and artist\n" +
                        "• Lyrics: add .lrc files or paste text, with synced highlighting\n" +
                        "• Swipe the album art to change song, double-tap it to like\n" +
                        "• Save your queue as a playlist, or clear what's coming up\n" +
                        "• Hide songs you don't want in the library\n" +
                        "• Song info: format, bitrate, size\n" +
                        "• Launcher shortcuts: Shuffle all and Search"
                    else if (versionName().startsWith("1.2"))
                        "Small polish and fixes. This update arrived through Lumen's built-in updater."
                    else
                        "• Automatic updates from GitHub\n" +
                        "• Equalizer, bass boost and loudness boost\n" +
                        "• Skip silence\n" +
                        "• Resume exactly where you left off\n" +
                        "• Most played and History in your library\n" +
                        "• Sleep timer: end of this song\n" +
                        "• Backup and restore\n" +
                        "• Share songs, and jump to more from an artist or album\n" +
                        "• ALAC (Apple Lossless) playback"
                )
                .setPositiveButton("Nice", null)
                .show()
        }
    }

    // ---------- back ----------
    private fun setupBack() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    sheet.isShown -> sheet.dismiss(true)
                    playerOpen -> closePlayer(0f)
                    searching -> closeSearch()
                    page == 1 && vm.detailId.value != null -> closePlaylist()
                    page != 0 -> { binding.tabBar.select(0, true); switchPage(0) }
                    else -> moveTaskToBack(true)
                }
            }
        })
    }
}
