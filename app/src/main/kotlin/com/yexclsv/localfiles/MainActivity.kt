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
import com.yexclsv.localfiles.data.*
import com.yexclsv.localfiles.databinding.ActivityMainBinding
import com.yexclsv.localfiles.ui.*
import kotlinx.coroutines.Job
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

        requestPerms()
        handleIntent(intent)
        showLastCrash()
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
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_VIEW && i.data != null) {
            val u = i.data!!
            persist(u)
            vm.playExternal(u)
            i.data = null
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
        b.topBar.requestLayout()
        b.tabBar.requestLayout()
        b.miniPlayer.requestLayout()
        b.player.playerContent.setPadding(0, topInset, 0, bottomInset + 8.dp)
        applyPagePadding()
    }

    private fun applyPagePadding() {
        val b = binding
        val libTop = topInset + 12.dp + barHeight(true) + 10.dp
        val otherTop = topInset + 12.dp + barHeight(false) + 10.dp
        val bottom = bottomInset + 12.dp + 64.dp + 16.dp + if (hasMini) 72.dp else 0
        b.pageLibrary.list.setPadding(0, libTop, 0, bottom)
        b.pageLibrary.emptyState.setPadding(40.dp, libTop, 40.dp, bottom)
        b.pagePlaylists.playlistGrid.setPadding(12.dp, otherTop, 12.dp, bottom)
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
        chip("Recent") { vm.listState.update { it.copy(filter = LibraryFilter.RECENT) } }.tag = LibraryFilter.RECENT
        chip("Sort", R.drawable.ic_sort) { showSortMenu() }.tag = "sort"
        refreshChips()
    }

    private fun refreshChips() {
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

    private fun updateTopBar() {
        val b = binding
        val detail = page == 1 && vm.detailId.value != null
        b.title.text = when {
            page == 0 -> "Library"
            page == 1 && detail -> playlistRowsNow.firstOrNull { it.id == vm.detailId.value }?.name ?: "Playlist"
            page == 1 -> "Playlists"
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
        b.btnAdd.visibility = if (page == 0 || (page == 1 && !detail)) View.VISIBLE else View.GONE
        b.btnBack.visibility = if (detail) View.VISIBLE else View.GONE
        if (page == 0) {
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
        t.setTabs(listOf(R.drawable.ic_music_note to "Library", R.drawable.ic_queue_music to "Playlists", R.drawable.ic_settings to "Settings"))
        t.onSelected = { switchPage(it) }
    }

    private fun switchPage(i: Int) {
        if (i == page) return
        closeSearch()
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
        if (r.isFav) return
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
        if (inPlaylist != null && inPlaylist != "fav")
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
        sheet.show("Sleep timer", opts.map { (m, label) ->
            ActionSheet.Item(label, selected = m == 0 && !active) {
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
        if (q.isEmpty()) {
            toast("Nothing queued after this song")
            return
        }
        sheet.show("Up next", q.map { (i, t) -> ActionSheet.Item("${t.title} — ${t.artist}") { vm.jumpTo(i) } }, sheetBackdrop())
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
                    vm.playlistRows.collect { rows ->
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
